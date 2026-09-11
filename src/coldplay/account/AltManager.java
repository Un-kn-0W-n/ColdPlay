package coldplay.account;

import coldplay.config.JsonStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Stores UUID-deduplicated accounts in {@code <mcDataDir>/coldplay/alts.json}, newest first.
 * Each account kind has its own size cap so cracked accounts cannot evict premium refresh tokens.
 */
public final class AltManager {
    private static final AltManager INSTANCE = new AltManager();
    private static final int MAX_SIZE = 50;

    private final List<Alt> alts = new ArrayList<Alt>();
    private JsonStore<Alt[]> store;
    private boolean loaded;

    private AltManager() {
    }

    public static AltManager getInstance() {
        return INSTANCE;
    }

    public synchronized void load(final File mcDataDir) {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        this.store = new JsonStore<Alt[]>(
                new File(new File(mcDataDir, "coldplay"), "alts.json"),
                Alt[].class,
                new Supplier<Alt[]>() {
                    public Alt[] get() {
                        return new Alt[0];
                    }
                },
                "alts");

        Alt[] parsed = this.store.load();
        this.alts.clear();
        for (Alt alt : parsed) {
            if (alt != null && alt.getUuid() != null) {
                this.alts.add(alt);
            }
        }
    }

    /**
     * Replacing an account with an access-token-only login must retain its saved refresh token.
     */
    public synchronized void upsert(final Alt alt) {
        if (alt == null || alt.getUuid() == null) {
            return;
        }
        for (int i = 0; i < this.alts.size(); i++) {
            final Alt previous = this.alts.get(i);
            if (alt.getUuid().equals(previous.getUuid())) {
                if (alt.getRefreshToken().isEmpty() && !previous.getRefreshToken().isEmpty()) {
                    alt.setRefreshToken(previous.getRefreshToken());
                }
                this.alts.remove(i);
                this.alts.add(0, alt);
                trim(alt.isCracked());
                save();
                return;
            }
        }
        this.alts.add(0, alt);
        trim(alt.isCracked());
        save();
    }

    public synchronized boolean remove(final String uuid) {
        if (uuid == null) {
            return false;
        }
        final Iterator<Alt> it = this.alts.iterator();
        while (it.hasNext()) {
            if (uuid.equals(it.next().getUuid())) {
                it.remove();
                save();
                return true;
            }
        }
        return false;
    }

    /** A snapshot copy of the saved alts of one kind (most-recent first). */
    public synchronized List<Alt> getAlts(final boolean cracked) {
        final List<Alt> matching = new ArrayList<Alt>();
        for (Alt alt : this.alts) {
            if (alt.isCracked() == cracked) {
                matching.add(alt);
            }
        }
        return Collections.unmodifiableList(matching);
    }

    private void trim(final boolean cracked) {
        int kept = 0;
        final Iterator<Alt> it = this.alts.iterator();
        while (it.hasNext()) {
            if (it.next().isCracked() == cracked && ++kept > MAX_SIZE) {
                it.remove();
            }
        }
    }

    private void save() {
        if (this.store != null) {
            this.store.save(this.alts.toArray(new Alt[this.alts.size()]));
        }
    }
}
