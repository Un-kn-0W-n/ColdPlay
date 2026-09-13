package coldplay.util;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

/** Player health from the HEARTS sidebar, else the below-name score, else vanilla health. */
public final class HealthResolver {

    private HealthResolver() {
    }

    public static float resolve(EntityLivingBase entity) {
        if (entity instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) entity;
            Scoreboard scoreboard = p.getWorldScoreboard();
            Float v = fromSlot(scoreboard, p, 1, true);
            if (v == null) {
                v = fromSlot(scoreboard, p, 2, false);
            }
            if (v != null) {
                return v;
            }
        }
        return entity.getHealth();
    }

    private static Float fromSlot(Scoreboard scoreboard, EntityPlayer p, int slot, boolean requireHearts) {
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
        if (objective == null) {
            return null;
        }
        if (requireHearts && objective.getRenderType() != IScoreObjectiveCriteria.EnumRenderType.HEARTS) {
            return null;
        }
        String name = p.getName();
        if (!scoreboard.entityHasObjective(name, objective)) {
            return null; // getValueFromObjective would create a 0 score, which reads as dead
        }
        return (float) scoreboard.getValueFromObjective(name, objective).getScorePoints();
    }
}
