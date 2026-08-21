package database

import (
	"database/sql"
	"encoding/json"
	"log"
	"sync"

	_ "github.com/lib/pq"
	"github.com/zeon/slugyzeon-ytcdn/config"
)

var (
	DB         *sql.DB
	cacheMutex sync.RWMutex
	metaCache  = make(map[string]*TrackData)
)

func InitDB() {
	var err error
	DB, err = sql.Open("postgres", config.DatabaseURL)
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}

	err = DB.Ping()
	if err != nil {
		log.Fatalf("Database is unreachable: %v", err)
	}

	log.Println("Successfully connected to the database")
	createTables()
}

func createTables() {
	_, err := DB.Exec(`CREATE TABLE IF NOT EXISTS tracks (video_id VARCHAR(20) PRIMARY KEY, metadata JSONB NOT NULL);`)
	if err != nil {
		log.Fatalf("Failed to create tables: %v", err)
	}
}

func GetTotalCount() (int64, error) {
	var count int64
	err := DB.QueryRow(`SELECT COUNT(*) FROM tracks`).Scan(&count)
	return count, err
}

func GetMetadata(videoId string) (*TrackData, error) {
	cacheMutex.RLock()
	cachedData, exists := metaCache[videoId]
	cacheMutex.RUnlock()
	if exists {
		return cachedData, nil
	}

	var metadataJSON string
	err := DB.QueryRow(`SELECT metadata FROM tracks WHERE video_id = $1`, videoId).Scan(&metadataJSON)
	if err != nil {
		return nil, err
	}

	var trackData TrackData
	if err := json.Unmarshal([]byte(metadataJSON), &trackData); err != nil {
		return nil, err
	}

	cacheMutex.Lock()
	metaCache[videoId] = &trackData
	cacheMutex.Unlock()

	return &trackData, nil
}

func GetMediaUrl(videoId string) (string, error) {
	trackData, err := GetMetadata(videoId)
	if err != nil {
		return "", err
	}
	return trackData.MediaUrl, nil
}

func SaveTrack(videoId string, metadata TrackData) error {
	payloadJSON, err := json.Marshal(metadata)
	if err != nil {
		return err
	}

	_, err = DB.Exec(`INSERT INTO tracks (video_id, metadata) VALUES ($1, $2) ON CONFLICT (video_id) DO UPDATE SET metadata = EXCLUDED.metadata;`, videoId, payloadJSON)
	if err == nil {
		cacheMutex.Lock()
		metaCache[videoId] = &metadata
		cacheMutex.Unlock()
	}
	return err
}