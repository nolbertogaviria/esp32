package net.osmand.aidl;

import net.osmand.aidl.search.SearchResult;
import net.osmand.aidl.gpx.AGpxBitmap;
import net.osmand.aidl.navigation.ADirectionInfo;
import net.osmand.aidl.navigation.OnVoiceNavigationParams;

interface IOsmAndAidlCallback {
    void onSearchComplete(in List<SearchResult> resultSet);
    void onUpdate();
    void onAppInitialized();
    void onGpxBitmapCreated(in AGpxBitmap bitmap);
    void updateNavigationInfo(in ADirectionInfo directionInfo);
    void onContextMenuButtonClicked(in int buttonId, String pointId, String layerId);
    void onVoiceRouterNotify(in OnVoiceNavigationParams params);
}
