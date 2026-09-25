# External subtitle restore (must not be stripped/renamed — reflection + log tags)
-keep class com.fongmi.android.tv.playback.SubtitleRestoreCoordinator { *; }
-keep class com.fongmi.android.tv.playback.SubtitleRestorePolicy { *; }
-keep class com.fongmi.android.tv.playback.SubtitleSource { *; }
-keep class com.fongmi.android.tv.subtitle.AssrtSubtitleMatch { *; }
-keepclassmembers class com.fongmi.android.tv.player.PlayerManager {
    void setSub(...);
    *** spec;
}
-keepclassmembers class * extends com.fongmi.android.tv.ui.activity.VideoActivity {
    *** mHistory;
}
