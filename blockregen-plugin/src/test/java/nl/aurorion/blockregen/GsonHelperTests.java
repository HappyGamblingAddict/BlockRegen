package nl.aurorion.blockregen;

import com.google.gson.GsonBuilder;
import nl.aurorion.blockregen.util.GsonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GsonHelperTests {

    @Test
    void missingListFileCompletesWithEmptyList(@TempDir Path directory) throws Exception {
        GsonHelper helper = new GsonHelper(new GsonBuilder());

        List<String> result = helper.loadListAsync(directory.resolve("missing.json").toString(), String.class)
                .get(1, TimeUnit.SECONDS);

        assertTrue(result.isEmpty());
    }
}
