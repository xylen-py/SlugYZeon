package handlers

import (
	"database/sql"
	"regexp"

	"github.com/gofiber/fiber/v2"
	"github.com/zeon/slugyzeon-ytcdn/database"
)

var videoIdRegex = regexp.MustCompile(`^[a-zA-Z0-9_-]{11}$`)

func GetMetadata(c *fiber.Ctx) error {
	videoId := c.Params("videoId")
	if !videoIdRegex.MatchString(videoId) {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid Video ID"})
	}

	metadata, err := database.GetMetadata(videoId)
	if err != nil {
		if err == sql.ErrNoRows {
			return c.Status(404).JSON(fiber.Map{"error": "Track not found in cache"})
		}
		return c.Status(500).JSON(fiber.Map{"error": "Database error"})
	}

	return c.JSON(metadata)
}

func StreamAudio(c *fiber.Ctx) error {
	videoId := c.Params("videoId")
	if !videoIdRegex.MatchString(videoId) {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid Video ID"})
	}

	mediaUrl, err := database.GetMediaUrl(videoId)
	if err != nil {
		if err == sql.ErrNoRows {
			return c.Status(404).JSON(fiber.Map{"error": "Audio stream not found"})
		}
		return c.Status(500).JSON(fiber.Map{"error": "Database error"})
	}

	if mediaUrl == "" {
		return c.Status(404).JSON(fiber.Map{"error": "Media URL not found in metadata"})
	}

	return c.Redirect(mediaUrl, 302)
}

func UploadCache(c *fiber.Ctx) error {
	videoId := c.Params("videoId")
	if !videoIdRegex.MatchString(videoId) {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid Video ID format"})
	}

	var payload database.TrackData
	if err := c.BodyParser(&payload); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid JSON payload"})
	}

	if payload.MediaUrl == "" {
		return c.Status(400).JSON(fiber.Map{"error": "Missing mediaUrl in payload"})
	}

	if err := database.SaveTrack(videoId, payload); err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Failed to save track data"})
	}

	return c.Status(200).JSON(fiber.Map{"status": "ok"})
}