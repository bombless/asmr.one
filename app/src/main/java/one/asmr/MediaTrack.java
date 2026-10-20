package one.asmr;

import java.io.Serializable;

public class MediaTrack implements Serializable {
    public final String id;
    public final String title;
    public final String streamUrl;
    public final String downloadUrl;
    public final double duration;
    public final String workId;
    public final String workTitle;
    public final String artist;
    public final String coverUrl;

    public MediaTrack(String id, String title, String streamUrl, String downloadUrl, double duration,
                      String workId, String workTitle, String artist, String coverUrl) {
        this.id = id != null ? id : "";
        this.title = title != null ? title : "";
        this.streamUrl = streamUrl != null ? streamUrl : "";
        this.downloadUrl = downloadUrl != null ? downloadUrl : "";
        this.duration = duration;
        this.workId = workId != null ? workId : "";
        this.workTitle = workTitle != null ? workTitle : "";
        this.artist = artist != null ? artist : "";
        this.coverUrl = coverUrl != null ? coverUrl : "";
    }

    public String getPlaybackUrl() {
        return !streamUrl.isEmpty() ? streamUrl : downloadUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaTrack that = (MediaTrack) o;
        if (!id.isEmpty() && !that.id.isEmpty()) {
            return id.equals(that.id);
        }
        return title.equals(that.title) && getPlaybackUrl().equals(that.getPlaybackUrl());
    }

    @Override
    public int hashCode() {
        return (title + getPlaybackUrl()).hashCode();
    }
}
