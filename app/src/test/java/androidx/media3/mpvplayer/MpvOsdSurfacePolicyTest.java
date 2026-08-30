package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvOsdSurfacePolicyTest {

    @Test
    public void disabledSubtitlesDoNotNeedSurface() {
        assertFalse(MpvOsdSurfacePolicy.requiresSurface(
                true, "", "no", "", "no"));
    }

    @Test
    public void automaticSelectionNeedsSurfaceOnlyAfterTrackIsActive() {
        assertFalse(MpvOsdSurfacePolicy.requiresSurface(
                true, "", "auto", "", "no"));
        assertTrue(MpvOsdSurfacePolicy.requiresSurface(
                true, "3", "auto", "", "no"));
    }

    @Test
    public void eitherSubtitleTrackNeedsSurface() {
        assertTrue(MpvOsdSurfacePolicy.requiresSurface(
                true, "2", "2", "", "no"));
        assertTrue(MpvOsdSurfacePolicy.requiresSurface(
                true, "", "no", "4", "4"));
    }

    @Test
    public void hiddenSubtitlesDoNotNeedSurface() {
        assertFalse(MpvOsdSurfacePolicy.requiresSurface(
                false, "2", "2", "4", "4"));
    }

    @Test
    public void disabledSubtitleSelectionsDoNotQueryCurrentTracks() {
        assertFalse(MpvOsdSurfacePolicy.needsCurrentTrackQuery(
                true, "no", "no"));
        assertFalse(MpvOsdSurfacePolicy.needsCurrentTrackQuery(
                false, "auto", "auto"));
    }

    @Test
    public void automaticOrUnknownSelectionStillQueriesCurrentTracks() {
        assertTrue(MpvOsdSurfacePolicy.needsCurrentTrackQuery(
                true, "auto", "no"));
        assertTrue(MpvOsdSurfacePolicy.needsCurrentTrackQuery(
                true, "", "no"));
    }
}
