/**
 * ColdPlay's event bus: a small {@link coldplay.event.Event} hierarchy dispatched through
 * {@link coldplay.EventHandler} to {@link coldplay.event.EventTarget}-annotated handler methods.
 * Phased events ({@link coldplay.event.PhasedEvent}) fire a {@link coldplay.event.EventPhase#PRE}
 * and {@link coldplay.event.EventPhase#POST} pass around the vanilla action.
 */
package coldplay.event;
