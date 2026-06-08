package net.osmand.aidl.navigation;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Datos de la próxima maniobra de navegación.
 * OsmAnd serializa: distanceTo (int) → turnType (int) → isLeftSide (byte).
 * Debe coincidir EXACTAMENTE con el orden de escritura del lado del servidor.
 */
public class ADirectionInfo implements Parcelable {

    private int distanceTo;   // metros al próximo giro
    private int turnType;     // código OsmAnd TurnType (0=C, 1=TL, 4=TU, 8=TR, 11=EXIT_L, 12=EXIT_R …)
    private boolean isLeftSide; // true en países con tráfico por la izquierda

    public ADirectionInfo(int distanceTo, int turnType, boolean isLeftSide) {
        this.distanceTo  = distanceTo;
        this.turnType    = turnType;
        this.isLeftSide  = isLeftSide;
    }

    protected ADirectionInfo(Parcel in) {
        distanceTo  = in.readInt();
        turnType    = in.readInt();
        isLeftSide  = in.readByte() != 0;
    }

    public static final Creator<ADirectionInfo> CREATOR = new Creator<ADirectionInfo>() {
        @Override
        public ADirectionInfo createFromParcel(Parcel in) { return new ADirectionInfo(in); }
        @Override
        public ADirectionInfo[] newArray(int size) { return new ADirectionInfo[size]; }
    };

    public int getDistanceTo() { return distanceTo; }
    public int getTurnType()   { return turnType;   }
    public boolean isLeftSide() { return isLeftSide; }

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(distanceTo);
        dest.writeInt(turnType);
        dest.writeByte((byte) (isLeftSide ? 1 : 0));
    }
}
