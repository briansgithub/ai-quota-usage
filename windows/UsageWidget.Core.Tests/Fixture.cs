namespace UsageWidget.Tests;

internal static class Fixture
{
    public static string Load(string name)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Fixtures", name);
        if (!File.Exists(path))
            throw new FileNotFoundException($"Missing fixture {name}", path);
        return File.ReadAllText(path);
    }
}
