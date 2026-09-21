package coldplay.module.combat;

import coldplay.broker.OutboundDelay;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.RangeSetting;

public class FakeLag extends Module {

    private final RangeSetting delay = add(new RangeSetting("Delay", 150.0, 250.0, 50.0, 1000.0, 25.0).unit("ms")
            .describe("How late each of your packets reaches the server, rolled between the thumbs."));

    public FakeLag() {
        super("FakeLag", Category.COMBAT, "Sends your packets to the server late but in order, so everyone sees you lagging.");
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
        if (event.isPre()) {
            OutboundDelay.getInstance().release();
        }
    }
}
