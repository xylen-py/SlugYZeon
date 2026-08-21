package config

import (
	"os"
	"github.com/joho/godotenv"
)

var (
	MasterKey   string
	Port        string
	DatabaseURL string
)

func Load() {
	_ = godotenv.Load()
	MasterKey = getEnv("MASTER_KEY", "49vK82mP9xQ2sL7w")
	Port = getEnv("PORT", "3000")
	DatabaseURL = getEnv("DATABASE_URL", "")
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}