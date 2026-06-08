package net.osmand.aidl.search;

import android.os.Parcel;
import android.os.Parcelable;

/** Stub mínimo requerido por el código generado de IOsmAndAidlCallback. */
public class SearchResult implements Parcelable {
    public static final Creator<SearchResult> CREATOR = new Creator<SearchResult>() {
        @Override public SearchResult createFromParcel(Parcel in) { return new SearchResult(in); }
        @Override public SearchResult[] newArray(int size) { return new SearchResult[size]; }
    };
    protected SearchResult(Parcel in) {}
    public SearchResult() {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
}
