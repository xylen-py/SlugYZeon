package main

import (
	"log"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/zeon/slugyzeon-ytcdn/config"
	"github.com/zeon/slugyzeon-ytcdn/database"
	"github.com/zeon/slugyzeon-ytcdn/handlers"
	"github.com/zeon/slugyzeon-ytcdn/middleware"
)

func main() {
	config.Load()

	database.InitDB()

	app := fiber.New(fiber.Config{
		BodyLimit: 100 * 1024 * 1024,
	})

	app.Use(recover.New())
	app.Use(logger.New())

	api := app.Group("/api/v1")
	api.Get("/status", handlers.StatusHandler)
	api.Get("/metadata/:videoId", handlers.GetMetadata)
	api.Get("/stream/:videoId", handlers.StreamAudio)
	api.Post("/upload/:videoId", middleware.RequireMasterKey(), handlers.UploadCache)
	log.Printf("SlugYZeon-YTCDN is starting on port %s...", config.Port)
	log.Fatal(app.Listen(":" + config.Port))
}