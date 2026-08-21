package database

type TrackRecord struct {
	VideoID  string    `json:"videoId"`
	Metadata TrackData `json:"metadata"`
}

type TrackData struct {
	Title      string `json:"title"`
	Author     string `json:"author"`
	Length     int64  `json:"length"`
	MediaUrl   string `json:"mediaUrl"`
	ArtworkUrl string `json:"artworkUrl,omitempty"`
	ISRC       string `json:"isrc,omitempty"`
}