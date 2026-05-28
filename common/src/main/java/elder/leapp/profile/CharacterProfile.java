package elder.leapp.profile;

// CharacterProfile.java
// The data model for one character profile.
// Each profile represents a separate character a player can maintain
// across different worlds — with their own inventory and player state.
//
// Fields:
//   displayName      — shown in the profile selector screen
//   label            — optional short tag (e.g. "Survival", "Creative")
//   datBlob          — raw bytes of the player's .dat file for this character
//   lastUsedAddress  — the world address this profile was last used on
//   lastUsedTime     — Unix timestamp of last use
//   profileUuid      — unique ID for this profile, also the filename on disk
//
// Profiles are stored as .nbt files at:
//   .minecraft/leappad/profiles/[profileUuid].nbt

import net.minecraft.nbt.CompoundTag;

public class CharacterProfile {

    public String profileUuid;
    public String displayName;
    public String label;          // Optional — empty string if not set
    public byte[] datBlob;        // Raw player .dat file bytes — may be null if never synced
    public String lastUsedAddress;
    public long lastUsedTime;

    public CharacterProfile(String profileUuid, String displayName) {
        this.profileUuid = profileUuid;
        this.displayName = displayName;
        this.label = "";
        this.datBlob = null;
        this.lastUsedAddress = "";
        this.lastUsedTime = 0L;
    }

    // -------------------------------------------------------
    // NBT serialization
    // -------------------------------------------------------

    // Writes this profile to an NBT compound tag for saving to disk
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("uuid", profileUuid);
        tag.putString("displayName", displayName);
        tag.putString("label", label);
        tag.putString("lastUsedAddress", lastUsedAddress);
        tag.putLong("lastUsedTime", lastUsedTime);

        // datBlob may be null if the player created a profile but never connected anywhere
        if (datBlob != null) {
            tag.putByteArray("datBlob", datBlob);
        }

        return tag;
    }

    // Reads a CharacterProfile back from an NBT compound tag
    public static CharacterProfile fromNbt(CompoundTag tag) {
        String uuid = tag.getString("uuid");
        String displayName = tag.getString("displayName");

        CharacterProfile profile = new CharacterProfile(uuid, displayName);
        profile.label = tag.getString("label");
        profile.lastUsedAddress = tag.getString("lastUsedAddress");
        profile.lastUsedTime = tag.getLong("lastUsedTime");

        // datBlob is absent for brand-new profiles that haven't connected yet
        if (tag.contains("datBlob")) {
            profile.datBlob = tag.getByteArray("datBlob");
        }

        return profile;
    }
}
