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
}
