package config

import (
	"os"
	"github.com/joho/godotenv"
)

var (
	MasterKey string
	Port      string
	CacheDir  string
)

func Load() {
	_ = godotenv.Load()
	MasterKey = getEnv("MASTER_KEY", "SUPER_SECRET_MASTER_KEY_CHANGE_ME")
	Port = getEnv("PORT", "3000")
	CacheDir = getEnv("CACHE_DIR", "./cache")
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}