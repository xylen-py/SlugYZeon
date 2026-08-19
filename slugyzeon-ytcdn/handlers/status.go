package handlers

import (
	"os"
	"runtime"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/zeon/slugyzeon-ytcdn/config"
)

var startTime = time.Now()

func StatusHandler(c *fiber.Ctx) error {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)

	trackCount := 0
	entries, err := os.ReadDir(config.CacheDir)
	if err == nil {
		for _, e := range entries {
			if e.IsDir() {
				trackCount++
			}
		}
	}

	return c.JSON(fiber.Map{
		"status":       "online",
		"uptime":       time.Since(startTime).String(),
		"memory_mb":    m.Alloc / 1024 / 1024,
		"goroutines":   runtime.NumGoroutine(),
		"cpus":         runtime.NumCPU(),
		"total_tracks": trackCount,
	})
}