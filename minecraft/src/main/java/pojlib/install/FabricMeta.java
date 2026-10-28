package pojlib.install;

import com.google.gson.annotations.SerializedName;
import pojlib.APIHandler;
import pojlib.util.Constants;
import pojlib.util.Version;

public class FabricMeta {

    private static final APIHandler handler = new APIHandler(Constants.FABRIC_META_URL);

    public static class FabricVersion {
        @SerializedName("version")
        public String version;
        @SerializedName("stable")
        public boolean stable;
        @SerializedName("separator")
        public String separator;
    }

    public static FabricVersion[] getVersions() {
        return handler.get("versions/loader", FabricVersion[].class);
    }

    private static Version getVersionFromFabric(FabricVersion fabric) {
        if(fabric.separator.contains("+")) {
            // Only used pre-0.11, no use for us
            return null;
        }

        String[] verName = fabric.version.split("\\.");
        if(verName.length < 3) {
            return null;
        }
        int major = Integer.parseInt(verName[0]);
        int minor = Integer.parseInt(verName[1]);
        int patch = Integer.parseInt(verName[2]);

        return new Version(major, minor, patch);
    }

    public static FabricVersion getLatestVersion() {
        FabricVersion latest = null;
        for (FabricVersion version : getVersions()) {
            if(latest == null) {
                latest = version;
                continue;
            }

            Version newVer = getVersionFromFabric(version);
            Version latestVer = getVersionFromFabric(latest);

            if(newVer == null || latestVer == null)
                continue;

            if(latestVer.major < newVer.major) {
                latest = version;
            } else if(latestVer.major == newVer.major && latestVer.minor < newVer.major) {
                latest = version;
            } else if(latestVer.major == newVer.major && latestVer.minor == newVer.major && latestVer.patch < newVer.patch) {
                latest = version;
            }
        }
        return latest;
    }

    public static VersionInfo getVersionInfo(FabricVersion fabricVersion, String minecraftVersion) {
        return handler.get(String.format("versions/loader/%s/%s/profile/json", minecraftVersion, fabricVersion.version), VersionInfo.class);
    }
}