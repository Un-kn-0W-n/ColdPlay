package coldplay.module.combat;

import coldplay.broker.OutboundDelay;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.RangeSetting;

public class FakeLag extends Module {

    private final RangeSetting delay = add(new RangeSetting("Delay", 250.0, 350.0, 50.0, 2000.0, 5.0).unit("ms")
            .describe("How long each outgoing packet is held before it is sent, rolled between the thumbs."));

    public FakeLag() {
        super("FakeLag", Category.COMBAT, "Holds everything you send for a delay and lets it out in order, so you lag and your ping rises with it.");
        addAutoOff();
    }

    @Override
    public String getSuffix() {
        long lo = Math.round(delay.getLo());
        long hi = Math.round(delay.getHi());
        return (lo == hi ? String.valueOf(hi) : lo + "-" + hi) + delay.getUnit();
    }

    @Override
    protected void onEnable() {
        OutboundDelay.getInstance().start(delay::random);
    }

    @Override
    protected void onDisable() {
        OutboundDelay.getInstance().stop();
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            OutboundDelay.getInstance().release();
        }
    }
}
