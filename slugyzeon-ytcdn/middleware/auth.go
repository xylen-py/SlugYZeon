package middleware

import (
	"github.com/gofiber/fiber/v2"
	"github.com/zeon/slugyzeon-ytcdn/config"
)

func RequireMasterKey() fiber.Handler {
	return func(c *fiber.Ctx) error {
		authHeader := c.Get("Authorization")
		if authHeader != "Bearer "+config.MasterKey {
			return c.Status(401).JSON(fiber.Map{"error": "Unauthorized. Invalid Master Key."})
		}
		return c.Next()
	}
}