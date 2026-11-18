package app.spicetify.extension.spotify.localserver;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConfigFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void valuesRoundTripIncludingAwkwardCharacters() throws IOException {
        File file = new File(folder.getRoot(), ConfigFile.NAME);
        Map<String, String> values = new HashMap<>();
        values.put("url", "https://dav.example/música/");
        values.put("username", "us=er: with\\odd\nchars");
        values.put("empty", "");
        ConfigFile.write(file, values);
        assertEquals(values, ConfigFile.read(file));
    }

    @Test public void aMissingFileIsNullAndHasTheAbsentStamp() throws IOException {
        File file = new File(folder.getRoot(), ConfigFile.NAME);
        assertNull(ConfigFile.read(file));
        assertEquals(ConfigFile.Stamp.ABSENT, ConfigFile.stamp(file));
    }

    @Test public void replacingTheFileChangesItsStampAndLeavesNoTemporaryFile() throws IOException {
        File file = new File(folder.getRoot(), ConfigFile.NAME);
        Map<String, String> values = new HashMap<>();
        values.put("enabled", "true");
        ConfigFile.write(file, values);
        ConfigFile.Stamp before = ConfigFile.stamp(file);
        assertNotEquals(ConfigFile.Stamp.ABSENT, before);
        values.put("enabled", "false");
        ConfigFile.write(file, values);
        assertNotEquals(before, ConfigFile.stamp(file));
        assertEquals("false", ConfigFile.read(file).get("enabled"));
        assertArrayEquals(new String[]{ConfigFile.NAME}, folder.getRoot().list());
    }

    @Test public void aFailedWriteKeepsTheOldSettings() throws IOException {
        File file = new File(folder.getRoot(), ConfigFile.NAME);
        Map<String, String> values = new HashMap<>();
        values.put("enabled", "true");
        ConfigFile.write(file, values);
        File missing = new File(new File(folder.getRoot(), "no-such-folder"), ConfigFile.NAME);
        try {
            ConfigFile.write(missing, values);
            fail("Writing into a missing folder must fail");
        } catch (IOException expected) {
            // fine
        }
        assertEquals("true", ConfigFile.read(file).get("enabled"));
    }

    @Test public void aDamagedFileIsAnIoErrorNotACrash() throws IOException {
        File file = new File(folder.getRoot(), ConfigFile.NAME);
        Files.write(file.toPath(), "url=\\uZZZZ\n".getBytes("ISO-8859-1"));
        try {
            ConfigFile.read(file);
            fail("A damaged file must be reported");
        } catch (IOException expected) {
            // fine
        }
    }
}
