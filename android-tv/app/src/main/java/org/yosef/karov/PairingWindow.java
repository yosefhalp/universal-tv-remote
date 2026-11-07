package org.yosef.karov;

import java.security.SecureRandom;
import java.util.Locale;

// החלון חי בזיכרון בלבד. ניתוק רשת אינו מאפס את מספר הניסיונות, ואתחול מבטל את הקוד הקצר.
final class PairingWindow {
    final String version;
    final long expires;
    final String password = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
    String prefix = "";
    String request = "";
    boolean registered;
    private int attempts;

    PairingWindow(String version, long expires) {
        this.version = version;
        this.expires = expires;
    }

    boolean valid(String currentVersion, long now) {
        return !version.isEmpty() && version.equals(currentVersion) && now < expires;
    }

    boolean available() {
        return attempts < 5;
    }

    boolean attempt(String currentVersion, String requestedPrefix, long now) {
        if (!valid(currentVersion, now)
                || !registered
                || !available()
                || !prefix.equals(requestedPrefix)) return false;
        attempts++;
        return true;
    }

    String code() {
        return prefix + password;
    }
}
