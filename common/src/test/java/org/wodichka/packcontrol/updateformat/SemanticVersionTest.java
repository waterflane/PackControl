package org.wodichka.packcontrol.updateformat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionTest {
    @Test
    void followsSemverPrecedenceRules() {
        List<String> ordered = List.of(
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-alpha.beta",
                "1.0.0-beta",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0-rc.1",
                "1.0.0"
        );
        for (int index = 0; index < ordered.size() - 1; index++) {
            assertTrue(
                    SemanticVersion.parse(ordered.get(index))
                            .compareTo(SemanticVersion.parse(ordered.get(index + 1))) < 0
            );
        }
    }

    @Test
    void ignoresBuildMetadataForPrecedenceAndSupportsLargeComponents() {
        assertEquals(
                0,
                SemanticVersion.parse("999999999999999999999.2.3+first")
                        .compareTo(SemanticVersion.parse("999999999999999999999.2.3+second"))
        );
        assertEquals("1.2.3-beta.1+build", SemanticVersion.parse("1.2.3-beta.1+build").toString());
    }

    @Test
    void rejectsLeadingZeroesAndIncompleteVersions() {
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("01.2.3"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.2"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.2.3-beta.01"));
    }
}
