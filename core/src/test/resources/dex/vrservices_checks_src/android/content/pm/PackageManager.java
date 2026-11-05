package android.content.pm;
public abstract class PackageManager {
    public abstract boolean hasSystemFeature(String name);
    public abstract boolean hasSystemFeature(String name, int version);
}
