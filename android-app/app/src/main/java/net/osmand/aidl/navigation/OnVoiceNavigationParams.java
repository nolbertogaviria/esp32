package net.osmand.aidl.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/** Stub mínimo requerido por el código generado de IOsmAndAidlCallback. */
public class OnVoiceNavigationParams implements Parcelable {
    public static final Creator<OnVoiceNavigationParams> CREATOR = new Creator<OnVoiceNavigationParams>() {
        @Override public OnVoiceNavigationParams createFromParcel(Parcel in) { return new OnVoiceNavigationParams(in); }
        @Override public OnVoiceNavigationParams[] newArray(int size) { return new OnVoiceNavigationParams[size]; }
    };
    protected OnVoiceNavigationParams(Parcel in) {}
    public OnVoiceNavigationParams() {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
}
