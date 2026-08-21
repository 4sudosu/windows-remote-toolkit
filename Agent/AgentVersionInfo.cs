using System.Reflection;

namespace RuntimeBroker;

public static class AgentVersionInfo
{
    public static string Version
    {
        get
        {
            var asm = Assembly.GetExecutingAssembly();
            var info = asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>();
            var v = info?.InformationalVersion ?? asm.GetName().Version?.ToString() ?? "0.0.0.0";
            var idx = v.IndexOf('+');
            if (idx > 0) v = v.Substring(0, idx);
            return v;
        }
    }

    public static bool IsNewerThan(string candidate, string current)
        => Compare(candidate, current) > 0;

    public static int Compare(string a, string b)
    {
        var pa = ToParts(a);
        var pb = ToParts(b);
        for (int i = 0; i < 4; i++)
        {
            if (pa[i] < pb[i]) return -1;
            if (pa[i] > pb[i]) return 1;
        }
        return 0;
    }

    private static int[] ToParts(string v)
    {
        var parts = (v ?? "").Split('.');
        var arr = new int[4];
        for (int i = 0; i < 4; i++)
        {
            if (i < parts.Length && int.TryParse(parts[i], out var n)) arr[i] = n;
            else arr[i] = 0;
        }
        return arr;
    }
}