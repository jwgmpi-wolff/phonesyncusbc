using PhoneSyncDataReader;

namespace PhoneSyncDataReader.Tests;

public sealed class JsonRecordFlattenerTests
{
    [Fact]
    public void CanonicalHashIgnoresObjectPropertyOrder()
    {
        ParsedRecord Parse(string json)
        {
            ParsedRecord? parsed = null;
            JsonRecordFlattener.Flatten(new StringReader(json), RecordKind.Message, "Messages", record => parsed = record);
            return Assert.IsType<ParsedRecord>(parsed);
        }

        var first = JsonRecordFlattener.CanonicalHash(Parse("{\"sender\":\"Ada\",\"body\":\"Hello\"}"));
        var reordered = JsonRecordFlattener.CanonicalHash(Parse("{\"body\":\"Hello\",\"sender\":\"Ada\"}"));
        var changed = JsonRecordFlattener.CanonicalHash(Parse("{\"sender\":\"Ada\",\"body\":\"Goodbye\"}"));

        Assert.Equal(first, reordered);
        Assert.NotEqual(first, changed);
    }
}
