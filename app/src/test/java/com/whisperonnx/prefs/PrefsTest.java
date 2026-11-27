package com.whisperonnx.prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrefsTest {
    private final Prefs prefs = new Prefs(new FakeSharedPreferences());

    @Test public void defaults() {
        assertFalse(prefs.useServer());
        assertEquals(Prefs.ENGINE_WHISPER, prefs.localEngine());
        assertEquals(Prefs.DEFAULT_SERVER_URL, prefs.serverUrl());
        assertEquals(Prefs.DEFAULT_SERVER_MODEL, prefs.serverModel());
        assertEquals(Prefs.DEFAULT_SILENCE_MS, prefs.silenceMs());
        assertEquals(1, prefs.quickRecordEndpoint());
        assertEquals(0, prefs.sendTarget());
        assertTrue(prefs.toolbarExpanded());
        assertEquals(Prefs.DEFAULT_ENDPOINT_BODY, prefs.endpointText(1, Prefs.EndpointField.BODY));
        assertEquals("", prefs.endpointText(1, Prefs.EndpointField.URL));
        assertTrue(prefs.endpointCombine(1));
    }

    @Test public void choosingLocalAfterServerKeepsTheEngine() {
        prefs.setLocal(Prefs.ENGINE_PARAKEET);
        prefs.setUseServer(true);
        assertTrue(prefs.useServer());
        prefs.setUseServer(false);
        assertEquals(Prefs.ENGINE_PARAKEET, prefs.localEngine());
    }

    @Test public void endpointsAreStoredPerIndex() {
        prefs.setEndpointText(2, Prefs.EndpointField.URL, "http://x");
        assertEquals("", prefs.endpointText(1, Prefs.EndpointField.URL));
        assertEquals("http://x", prefs.endpointText(2, Prefs.EndpointField.URL));
    }

    @Test public void forgottenWidgetsFallBackToNoChoice() {
        prefs.setWidgetEndpoint(7, 3);
        prefs.forgetWidgets(new int[]{7});
        assertEquals(0, prefs.widgetEndpoint(7));
    }

    @Test public void starAskedOnlyAfterAnUpdate() {
        assertFalse(prefs.starVersionIsOlderThan(19));
        prefs.setStarVersion(18);
        assertTrue(prefs.starVersionIsOlderThan(19));
        assertFalse(prefs.starVersionIsOlderThan(18));
    }
}
