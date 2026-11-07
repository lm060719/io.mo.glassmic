import io.mo.glassmic.data.audio.FolderIndexReconciler;
import io.mo.glassmic.data.db.AudioClipEntity;
import java.util.List;
import java.util.Set;

/** Host JVM checks; no Android emulator or device is started. */
public final class FolderIndexRegression {
    private static AudioClipEntity clip(String id, String uri, String name, boolean hidden, long size) {
        return new AudioClipEntity(id, "folder", name, "same.mp3", uri == null ? "folder/local.mp3" : "",
            "audio/mpeg", 1000L, size, 48000, 1, 1000L + size, (int) size, uri, 100L, hidden);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var renamed = clip("stable", "content://one", "User renamed", false, 10);
        var hidden = clip("hidden", "content://two", "Hidden during scan", true, 20);
        var imported = clip("imported", null, "Private copy", false, 30);
        var gone = clip("gone", "content://gone", "Deleted externally", false, 40);
        var unreadable = clip("unreadable", "content://offline", "Temporarily unreadable", false, 50);
        var scan = List.of(
            clip("stale-id", "content://one", "same", false, 100),
            clip("hidden", "content://two", "same", false, 200),
            clip("new", "content://new", "same", false, 300)
        );
        var plan = FolderIndexReconciler.INSTANCE.reconcile(
            List.of(renamed, hidden, imported, gone, unreadable), scan,
            Set.of("content://one", "content://two", "content://new", "content://offline")
        );
        check(plan.getUpserts().size() == 3, "same-named files with different URIs must stay distinct");
        var updated = plan.getUpserts().get(0);
        check(updated.getId().equals("stable"), "refresh must retain selection identity");
        check(updated.getDisplayName().equals("User renamed"), "concurrent rename must survive refresh");
        check(updated.getSizeBytes() == 100, "changed source metadata must refresh");
        check(updated.getSortOrder() == 10 && updated.getCreatedAt() == 1010, "refresh must preserve ordering");
        check(plan.getUpserts().get(1).getHidden(), "concurrent removal must not be undone by scan");
        check(plan.getDeleteIds().equals(List.of("gone")), "delete only missing external clips, never unreadable or imported clips");

        var repeated = FolderIndexReconciler.INSTANCE.reconcile(plan.getUpserts(), scan,
            Set.of("content://one", "content://two", "content://new"));
        check(repeated.getUpserts().equals(plan.getUpserts()) && repeated.getDeleteIds().isEmpty(),
            "repeated refresh must be idempotent");
        var empty = FolderIndexReconciler.INSTANCE.reconcile(List.of(hidden, imported), List.of(), Set.of());
        check(empty.getDeleteIds().isEmpty(), "empty folder must retain tombstones and private copies");
        var failedProbe = FolderIndexReconciler.INSTANCE.reconcile(List.of(unreadable), List.of(), Set.of("content://offline"));
        check(failedProbe.getDeleteIds().isEmpty(), "failed metadata probe must not erase a listed file");
        System.out.println("Folder index regressions passed (identity, duplicates, edits, removals, missing files, read failures).");
    }
}
