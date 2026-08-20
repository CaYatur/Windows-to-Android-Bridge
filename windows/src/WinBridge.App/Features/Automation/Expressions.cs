using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace WinBridge.App.Features.Automation;

public sealed class ExpressionException(string message) : Exception(message);

/// <summary>
/// The expression language automations are written in.
///
/// A purpose-built evaluator rather than embedding a scripting engine, and that
/// is a security decision before it is anything else. An automation arrives from
/// a phone; handing that text to something that can reach the filesystem, the
/// network or reflection would make every other control in this feature
/// decorative. This grammar can read variables, compare them, and call a fixed
/// list of functions. There is no way to express anything else, so there is
/// nothing to sandbox.
///
/// Everything is loosely typed on purpose. Conditions get written as
/// <c>battery &lt; 20</c> against a value that may arrive as a string, and
/// failing on that would be pedantic about a comparison whose meaning is
/// obvious.
/// </summary>
public sealed class Expressions(Func<string, object?> resolveVariable)
{
    // Captured into a field: the parser is a nested type and cannot reach a
    // primary-constructor parameter of the class that encloses it.
    private readonly Func<string, object?> _resolve = resolveVariable;

    /// <summary>Bounded so a hostile or careless pattern cannot hang the run.</summary>
    private static readonly TimeSpan RegexBudget = TimeSpan.FromMilliseconds(250);

    public static readonly IReadOnlyList<string> Functions =
    [
        "contains", "startsWith", "endsWith", "matches", "lower", "upper", "trim",
        "len", "num", "int", "str", "bool", "split", "join", "replace", "indexOf",
        "substring", "min", "max", "abs", "round", "floor", "ceil", "rand",
        "now", "date", "env", "ifEmpty", "default", "isEmpty", "fileExists",
        "folderExists", "processRunning", "windowExists", "regexGroup", "lines",
    ];

    public object? Evaluate(string expression)
    {
        var parser = new Parser(expression, this);
        object? value = parser.ParseExpression();
        parser.ExpectEnd();
        return value;
    }

    public bool EvaluateCondition(string? expression)
    {
        if (string.IsNullOrWhiteSpace(expression)) return true;
        return Truthy(Evaluate(expression));
    }

    /// <summary>
    /// Replaces <c>{{ … }}</c> with the value of the expression inside.
    ///
    /// This is where a command line gets its final shape, which is why the
    /// confirmation dialog is shown the interpolated text and never the
    /// template: the template says <c>del {{target}}</c> and tells the user
    /// nothing about what is going to be deleted.
    /// </summary>
    public string Interpolate(string? text)
    {
        if (string.IsNullOrEmpty(text) || !text.Contains("{{", StringComparison.Ordinal)) return text ?? "";

        var output = new StringBuilder(text.Length);
        int index = 0;

        while (index < text.Length)
        {
            int open = text.IndexOf("{{", index, StringComparison.Ordinal);
            if (open < 0) { output.Append(text, index, text.Length - index); break; }

            int close = text.IndexOf("}}", open + 2, StringComparison.Ordinal);
            if (close < 0) { output.Append(text, index, text.Length - index); break; }

            output.Append(text, index, open - index);
            string inner = text[(open + 2)..close].Trim();
            output.Append(Stringify(Evaluate(inner)));
            index = close + 2;
        }

        return output.ToString();
    }

    public static bool Truthy(object? value) => value switch
    {
        null => false,
        bool flag => flag,
        double number => Math.Abs(number) > double.Epsilon,
        string text => text.Length > 0 && !text.Equals("false", StringComparison.OrdinalIgnoreCase) && text != "0",
        IReadOnlyList<object?> list => list.Count > 0,
        _ => true,
    };

    public static string Stringify(object? value) => value switch
    {
        null => "",
        bool flag => flag ? "true" : "false",
        double number => number == Math.Floor(number) && Math.Abs(number) < 1e15
            ? ((long)number).ToString(CultureInfo.InvariantCulture)
            : number.ToString("0.####", CultureInfo.InvariantCulture),
        IReadOnlyList<object?> list => string.Join(",", list.Select(Stringify)),
        _ => value.ToString() ?? "",
    };

    private static double Number(object? value) => value switch
    {
        null => 0,
        bool flag => flag ? 1 : 0,
        double number => number,
        string text => double.TryParse(text, NumberStyles.Any, CultureInfo.InvariantCulture, out double parsed)
            ? parsed
            : 0,
        _ => 0,
    };

    private object? Call(string name, List<object?> args)
    {
        object? At(int index) => index < args.Count ? args[index] : null;
        string Text(int index) => Stringify(At(index));
        double Num(int index) => Number(At(index));

        return name switch
        {
            "contains" => At(0) is IReadOnlyList<object?> list
                ? list.Any(item => Stringify(item) == Text(1))
                : Text(0).Contains(Text(1), StringComparison.OrdinalIgnoreCase),
            "startsWith" => Text(0).StartsWith(Text(1), StringComparison.OrdinalIgnoreCase),
            "endsWith" => Text(0).EndsWith(Text(1), StringComparison.OrdinalIgnoreCase),
            "matches" => SafeRegex(Text(1)).IsMatch(Text(0)),
            "regexGroup" => RegexGroup(Text(0), Text(1), (int)Num(2)),
            "lower" => Text(0).ToLowerInvariant(),
            "upper" => Text(0).ToUpperInvariant(),
            "trim" => Text(0).Trim(),
            "len" => At(0) is IReadOnlyList<object?> items ? (double)items.Count : Text(0).Length,
            "num" or "int" => name == "int" ? Math.Truncate(Num(0)) : Num(0),
            "str" => Text(0),
            "bool" => Truthy(At(0)),
            "split" => (object)Text(0)
                .Split(Text(1).Length == 0 ? "," : Text(1))
                .Select(part => (object?)part)
                .ToList(),
            "lines" => (object)Text(0)
                .Split(['\n', '\r'], StringSplitOptions.RemoveEmptyEntries)
                .Select(part => (object?)part.Trim())
                .ToList(),
            "join" => At(0) is IReadOnlyList<object?> parts
                ? string.Join(Text(1), parts.Select(Stringify))
                : Text(0),
            "replace" => Text(0).Replace(Text(1), Text(2)),
            "indexOf" => (double)Text(0).IndexOf(Text(1), StringComparison.OrdinalIgnoreCase),
            "substring" => Substring(Text(0), (int)Num(1), args.Count > 2 ? (int)Num(2) : int.MaxValue),
            "min" => Math.Min(Num(0), Num(1)),
            "max" => Math.Max(Num(0), Num(1)),
            "abs" => Math.Abs(Num(0)),
            "round" => Math.Round(Num(0), args.Count > 1 ? (int)Num(1) : 0),
            "floor" => Math.Floor(Num(0)),
            "ceil" => Math.Ceiling(Num(0)),
            "rand" => args.Count >= 2
                ? Random.Shared.Next((int)Num(0), (int)Num(1) + 1)
                : Random.Shared.NextDouble(),
            "now" => (double)DateTimeOffset.Now.ToUnixTimeSeconds(),
            "date" => DateTime.Now.ToString(args.Count > 0 ? Text(0) : "yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture),
            "env" => Environment.GetEnvironmentVariable(Text(0)) ?? "",
            "isEmpty" => string.IsNullOrWhiteSpace(Text(0)),
            "ifEmpty" or "default" => string.IsNullOrWhiteSpace(Text(0)) ? At(1) : At(0),
            "fileExists" => File.Exists(Text(0)),
            "folderExists" => Directory.Exists(Text(0)),
            "processRunning" => Process.GetProcessesByName(
                Text(0).Replace(".exe", "", StringComparison.OrdinalIgnoreCase)).Length > 0,
            "windowExists" => WindowLookup?.Invoke(Text(0)) ?? false,
            _ => throw new ExpressionException($"unknown function \"{name}\""),
        };
    }

    /// <summary>Set by the engine so <c>windowExists</c> can reach the window list.</summary>
    public Func<string, bool>? WindowLookup { get; set; }

    private static string Substring(string text, int start, int length)
    {
        if (start < 0) start = Math.Max(0, text.Length + start);
        if (start >= text.Length) return "";
        return text.Substring(start, Math.Min(length, text.Length - start));
    }

    private static Regex SafeRegex(string pattern) => new(pattern, RegexOptions.None, RegexBudget);

    private static string RegexGroup(string input, string pattern, int group)
    {
        var match = SafeRegex(pattern).Match(input);
        return match.Success && group < match.Groups.Count ? match.Groups[group].Value : "";
    }

    // ---- parser ------------------------------------------------------------

    private sealed class Parser(string text, Expressions owner)
    {
        private int _at;

        public object? ParseExpression() => ParseOr();

        public void ExpectEnd()
        {
            SkipSpace();
            if (_at < text.Length) throw new ExpressionException($"unexpected \"{text[_at..]}\"");
        }

        private object? ParseOr()
        {
            object? left = ParseAnd();
            while (true)
            {
                if (Match("||") || MatchWord("or"))
                {
                    // Not short-circuiting: the right side still has to be parsed
                    // to know where the expression ends, so it is evaluated too.
                    // Every function here is side-effect free, so that is safe.
                    object? right = ParseAnd();
                    left = Truthy(left) || Truthy(right);
                }
                else return left;
            }
        }

        private object? ParseAnd()
        {
            object? left = ParseComparison();
            while (true)
            {
                if (Match("&&") || MatchWord("and"))
                {
                    object? right = ParseComparison();
                    left = Truthy(left) && Truthy(right);
                }
                else return left;
            }
        }

        private object? ParseComparison()
        {
            object? left = ParseAdditive();
            SkipSpace();

            foreach (string op in new[] { "==", "!=", "<=", ">=", "~=", "<", ">" })
            {
                if (!Match(op)) continue;
                object? right = ParseAdditive();
                return Compare(op, left, right);
            }

            if (MatchWord("contains"))
            {
                object? right = ParseAdditive();
                return left is IReadOnlyList<object?> list
                    ? list.Any(item => Stringify(item) == Stringify(right))
                    : Stringify(left).Contains(Stringify(right), StringComparison.OrdinalIgnoreCase);
            }

            return left;
        }

        private static object Compare(string op, object? left, object? right)
        {
            bool numeric = left is double || right is double ||
                (double.TryParse(Stringify(left), NumberStyles.Any, CultureInfo.InvariantCulture, out _) &&
                 double.TryParse(Stringify(right), NumberStyles.Any, CultureInfo.InvariantCulture, out _));

            if (op == "~=") return SafeRegex(Stringify(right)).IsMatch(Stringify(left));

            if (numeric)
            {
                double a = Number(left), b = Number(right);
                return op switch
                {
                    "==" => Math.Abs(a - b) < 1e-9,
                    "!=" => Math.Abs(a - b) >= 1e-9,
                    "<" => a < b,
                    "<=" => a <= b,
                    ">" => a > b,
                    ">=" => a >= b,
                    _ => false,
                };
            }

            int order = string.Compare(Stringify(left), Stringify(right), StringComparison.OrdinalIgnoreCase);
            return op switch
            {
                "==" => order == 0,
                "!=" => order != 0,
                "<" => order < 0,
                "<=" => order <= 0,
                ">" => order > 0,
                ">=" => order >= 0,
                _ => false,
            };
        }

        private object? ParseAdditive()
        {
            object? left = ParseMultiplicative();
            while (true)
            {
                SkipSpace();
                if (Match("+"))
                {
                    object? right = ParseMultiplicative();
                    // "+" concatenates when either side is text, which is what
                    // someone building a command line means by it.
                    left = left is string || right is string
                        ? Stringify(left) + Stringify(right)
                        : Number(left) + Number(right);
                }
                else if (Match("-")) left = Number(left) - Number(ParseMultiplicative());
                else return left;
            }
        }

        private object? ParseMultiplicative()
        {
            object? left = ParseUnary();
            while (true)
            {
                SkipSpace();
                if (Match("*")) left = Number(left) * Number(ParseUnary());
                else if (Match("/"))
                {
                    double divisor = Number(ParseUnary());
                    left = Math.Abs(divisor) < 1e-12 ? 0d : Number(left) / divisor;
                }
                else if (Match("%"))
                {
                    double divisor = Number(ParseUnary());
                    left = Math.Abs(divisor) < 1e-12 ? 0d : Number(left) % divisor;
                }
                else return left;
            }
        }

        private object? ParseUnary()
        {
            SkipSpace();
            if (Match("!") || MatchWord("not")) return !Truthy(ParseUnary());
            if (Match("-")) return -Number(ParseUnary());
            return ParsePrimary();
        }

        private object? ParsePrimary()
        {
            SkipSpace();
            if (_at >= text.Length) throw new ExpressionException("expression ended early");

            char c = text[_at];

            if (c == '(')
            {
                _at++;
                object? inner = ParseExpression();
                SkipSpace();
                if (_at >= text.Length || text[_at] != ')') throw new ExpressionException("missing )");
                _at++;
                return inner;
            }

            if (c is '\'' or '"') return ParseString(c);
            if (char.IsDigit(c)) return ParseNumber();
            if (char.IsLetter(c) || c == '_') return ParseIdentifier();

            throw new ExpressionException($"unexpected character \"{c}\"");
        }

        private string ParseString(char quote)
        {
            _at++;
            var builder = new StringBuilder();
            while (_at < text.Length && text[_at] != quote)
            {
                if (text[_at] == '\\' && _at + 1 < text.Length)
                {
                    _at++;
                    builder.Append(text[_at] switch
                    {
                        'n' => '\n',
                        't' => '\t',
                        'r' => '\r',
                        _ => text[_at],
                    });
                }
                else builder.Append(text[_at]);
                _at++;
            }
            if (_at >= text.Length) throw new ExpressionException("unterminated string");
            _at++;
            return builder.ToString();
        }

        private double ParseNumber()
        {
            int start = _at;
            while (_at < text.Length && (char.IsDigit(text[_at]) || text[_at] == '.')) _at++;
            return double.Parse(text[start.._at], CultureInfo.InvariantCulture);
        }

        private object? ParseIdentifier()
        {
            int start = _at;
            while (_at < text.Length && (char.IsLetterOrDigit(text[_at]) || text[_at] is '_' or '.')) _at++;
            string name = text[start.._at];

            SkipSpace();
            if (_at < text.Length && text[_at] == '(')
            {
                _at++;
                var args = new List<object?>();
                SkipSpace();
                if (_at < text.Length && text[_at] == ')') _at++;
                else
                {
                    while (true)
                    {
                        args.Add(ParseExpression());
                        SkipSpace();
                        if (_at < text.Length && text[_at] == ',') { _at++; continue; }
                        if (_at < text.Length && text[_at] == ')') { _at++; break; }
                        throw new ExpressionException($"malformed arguments to {name}()");
                    }
                }
                return owner.Call(name, args);
            }

            return name switch
            {
                "true" => true,
                "false" => false,
                "null" => null,
                _ => owner._resolve(name),
            };
        }

        private void SkipSpace()
        {
            while (_at < text.Length && char.IsWhiteSpace(text[_at])) _at++;
        }

        private bool Match(string token)
        {
            SkipSpace();
            if (_at + token.Length > text.Length) return false;
            if (text.AsSpan(_at, token.Length).SequenceEqual(token)) { _at += token.Length; return true; }
            return false;
        }

        /// <summary>
        /// Word operators need a boundary check, or <c>orange</c> parses as
        /// <c>or</c> followed by <c>ange</c>.
        /// </summary>
        private bool MatchWord(string word)
        {
            SkipSpace();
            if (_at + word.Length > text.Length) return false;
            if (!text.AsSpan(_at, word.Length).SequenceEqual(word)) return false;

            int after = _at + word.Length;
            if (after < text.Length && (char.IsLetterOrDigit(text[after]) || text[after] == '_')) return false;

            _at = after;
            return true;
        }
    }
}
