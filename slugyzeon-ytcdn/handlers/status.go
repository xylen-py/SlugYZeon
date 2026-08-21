package handlers

import (
	"runtime"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/zeon/slugyzeon-ytcdn/database"
)

var startTime = time.Now()

func StatusHandler(c *fiber.Ctx) error {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	trackCount, _ := database.GetTotalCount()

	return c.JSON(fiber.Map{
		"uptime":       time.Since(startTime).String(),
		"memory_mb":    m.Alloc / 1024 / 1024,
		"goroutines":   runtime.NumGoroutine(),
		"cpus":         runtime.NumCPU(),
		"total_tracks": trackCount,
	})
}