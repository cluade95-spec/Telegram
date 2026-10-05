package org.telegram.messenger.usage;
import org.junit.Test;
import static org.junit.Assert.*;
public class UsageSendIdentityTest {
    @Test public void schedulingFailuresIncomingAndLocalIdsNeverCount() {
        assertFalse(UsageSendIdentity.eligible(11,123,true,true,true,false));
        assertFalse(UsageSendIdentity.eligible(11,123,true,false,false,false));
        assertFalse(UsageSendIdentity.eligible(11,123,false,true,false,false));
        assertFalse(UsageSendIdentity.eligible(11,-123,true,true,false,false));
        assertFalse(UsageSendIdentity.eligible(0,123,true,true,false,false));
        assertTrue(UsageSendIdentity.eligible(11,123,true,true,false,false));
        assertTrue(UsageSendIdentity.eligible(11,-999,true,true,false,true));
    }
    @Test public void logicalIdentityIsStableAcrossReplayAndAccountSlots() {
        String key=UsageSendIdentity.digest("salt",11,-77,123,false);
        assertEquals(64,key.length());
        assertEquals(key,UsageSendIdentity.digest("salt",11,-77,123,false));
        assertNotEquals(key,UsageSendIdentity.digest("salt",22,-77,123,false));
        assertNotEquals(key,UsageSendIdentity.digest("salt",11,-88,123,false));
        assertNotEquals(key,UsageSendIdentity.digest("salt",11,-77,124,false));
        assertNotEquals(key,UsageSendIdentity.digest("salt",11,-77,123,true));
        assertNotEquals(key,UsageSendIdentity.digest("other salt",11,-77,123,false));
    }
}
