package handlers

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"

	"github.com/gofiber/fiber/v2"
	"github.com/zeon/slugyzeon-ytcdn/config"
)

var videoIdRegex = regexp.MustCompile(`^[a-zA-Z0-9_-]{11}$`)

func GetMetadata(c *fiber.Ctx) error {
	videoId := c.Params("videoId")
	if !videoIdRegex.MatchString(videoId) {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid Video ID"})
	}

	metadataPath := filepath.Join(config.CacheDir, videoId, "metadata.json")
	if _, err := os.Stat(metadataPath); os.IsNotExist(err) {
		return c.Status(404).JSON(fiber.Map{"error": "Track not found in cache"})
	}

	return c.SendFile(metadataPath)
}

func StreamAudio(c *fiber.Ctx) error {
	videoId := c.Params("videoId")
	if !videoIdRegex.MatchString(videoId) {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid Video ID"})
	}

	dirPath := filepath.Join(config.CacheDir, videoId)
	
	audioPath := filepath.Join(dirPath, "audio.webm")
	if _, err := os.Stat(audioPath); os.IsNotExist(err) {
		audioPath = filepath.Join(dirPath, "audio.m4a")
		if _, err := os.Stat(audioPath); os.IsNotExist(err) {
			return c.Status(404).JSON(fiber.Map{"error": "Audio file not found in cache"})
		}
	}

	return c.SendFile(audioPath)
}

func UploadCache(c *fiber.Ctx) error {
	videoId := c.Params("videoId")
	if !videoIdRegex.MatchString(videoId) {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid Video ID format"})
	}

	metadataStr := c.FormValue("metadata")
	if metadataStr == "" {
		return c.Status(400).JSON(fiber.Map{"error": "Missing metadata JSON in form"})
	}

	var dummy map[string]interface{}
	if err := json.Unmarshal([]byte(metadataStr), &dummy); err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Invalid JSON format in metadata"})
	}

	fileHeader, err := c.FormFile("audio")
	if err != nil {
		return c.Status(400).JSON(fiber.Map{"error": "Missing audio file in form"})
	}

	file, err := fileHeader.Open()
	if err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Failed to open uploaded file"})
	}
	
	magicBytes := make([]byte, 512)
	file.Read(magicBytes)
	file.Close()

	ext := filepath.Ext(fileHeader.Filename)
	if ext != ".webm" && ext != ".m4a" {
		ext = ".webm"
	}

	trackDir := filepath.Join(config.CacheDir, videoId)
	if err := os.MkdirAll(trackDir, 0755); err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Failed to create track directory"})
	}

	err = os.WriteFile(filepath.Join(trackDir, "metadata.json"), []byte(metadataStr), 0644)
	if err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Failed to write metadata"})
	}

	audioPath := filepath.Join(trackDir, "audio"+ext)
	if err := c.SaveFile(fileHeader, audioPath); err != nil {
		return c.Status(500).JSON(fiber.Map{"error": "Failed to save audio file"})
	}

	return c.Status(200).JSON(fiber.Map{
		"status": "success",
		"message": fmt.Sprintf("Cached %s successfully", videoId),
	})
}