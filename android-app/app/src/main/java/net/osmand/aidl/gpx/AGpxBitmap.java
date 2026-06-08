package net.osmand.aidl.gpx;

import android.os.Parcel;
import android.os.Parcelable;

/** Stub mínimo requerido por el código generado de IOsmAndAidlCallback. */
public class AGpxBitmap implements Parcelable {
    public static final Creator<AGpxBitmap> CREATOR = new Creator<AGpxBitmap>() {
        @Override public AGpxBitmap createFromParcel(Parcel in) { return new AGpxBitmap(in); }
        @Override public AGpxBitmap[] newArray(int size) { return new AGpxBitmap[size]; }
    };
    protected AGpxBitmap(Parcel in) {}
    public AGpxBitmap() {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
}
