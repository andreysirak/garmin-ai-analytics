package dev.garmin.evaluation;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationSuiteTest {
    @Test void loadsExistingVersionedSuite() throws Exception {
        EvaluationSuite suite = EvaluationSuite.load(Path.of("../evaluation/cases.json"));
        assertEquals(23, suite.document().path("cases").size());
    }
    @Test void rejectsDuplicateIds() throws Exception {
        Path file = Files.createTempFile("duplicate-evaluation", ".json");
        try {
            Files.writeString(file, """
                {"cases":[
                  {"id":"same","split":"regression","stage":"routing","question":"first"},
                  {"id":"same","split":"regression","stage":"routing","question":"second"}
                ]}
                """);
            assertThrows(IllegalArgumentException.class, () -> EvaluationSuite.load(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}