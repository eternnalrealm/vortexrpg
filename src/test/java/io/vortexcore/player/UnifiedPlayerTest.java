package io.vortexcore.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vortexcore.math.ExperienceCurve;
import io.vortexcore.persistence.model.PlayerSnapshot;
import io.vortexcore.progression.VortexAttribute;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnifiedPlayerTest {

    @Test
    void grantExperienceLevelsPlayerAcrossThresholds() {
        UnifiedPlayer player = new UnifiedPlayer(UUID.randomUUID(), "Architect");
        ExperienceCurve curve = ExperienceCurve.cubic(45.0D, 120.0D);

        UnifiedPlayer.ProgressionResult firstGain = player.grantExperience(164L, curve);
        UnifiedPlayer.ProgressionResult secondGain = player.grantExperience(1L, curve);

        assertEquals(1, firstGain.newLevel());
        assertEquals(0, firstGain.levelsGained());
        assertEquals(2, secondGain.newLevel());
        assertEquals(1, secondGain.levelsGained());
        assertEquals(165L, player.experience());
    }

    @Test
    void snapshotsPreserveMetadataStatsAndRevision() {
        UnifiedPlayer source = new UnifiedPlayer(UUID.randomUUID(), "Vortex");
        source.putMetadata("class", "mage");
        source.stat(VortexAttribute.WISDOM).setBase(10.0D);
        source.stat(VortexAttribute.WISDOM).addFlat(5.0D);
        source.stat(VortexAttribute.WISDOM).addMultiplier(0.5D);

        PlayerSnapshot persisted = source.toSnapshot().nextRevision();
        UnifiedPlayer restored = new UnifiedPlayer(source.uniqueId(), "Restored");
        restored.applySnapshot(persisted);

        assertEquals(1L, restored.snapshotRevision());
        assertEquals(10.0D, restored.stat(VortexAttribute.WISDOM).current());
        assertEquals("mage", restored.metadata().get("class"));
        assertTrue(restored.metadata().containsKey("class"));
        assertFalse(restored.metadata().containsKey("missing"));
    }
}
