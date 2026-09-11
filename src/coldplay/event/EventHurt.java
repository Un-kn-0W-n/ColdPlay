package coldplay.event;

/**
 * Fired the moment the LOCAL player's {@code hurtTime} is set client-side — i.e. "you just took a
 * hit", from any of the vanilla paths that signal it (health-drop via S06 in
 * {@code EntityPlayerSP.setPlayerSPHealth}, entity status 2 via S19 in
 * {@code EntityLivingBase.handleStatusUpdate}, hurt animation via S0B in
 * {@code EntityLivingBase.performHurtAnimation}). Listeners that need to react to being hit
 * (knockback mitigation, pausing an automated dig) subscribe here instead of each polling
 * {@code hurtTime} against a remembered value.
 *
 * <p>May fire more than once for the same hit when the server sends several of those packets;
 * listeners should be idempotent within a tick (set a flag / reset a countdown).
 */
public class EventHurt extends Event {
}
