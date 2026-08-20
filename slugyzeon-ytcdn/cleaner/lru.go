package cleaner

import (
	"log"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/shirou/gopsutil/v3/disk"
	"github.com/zeon/slugyzeon-ytcdn/config"
)

type CacheItem struct {
	Path  string
	Size  uint64
	MTime time.Time
}

func StartLRUCleaner(maxPercentage float64) {
	log.Printf("LRU Cleaner started. Max disk usage limit set to %.1f%%", maxPercentage)
	
	for {
		time.Sleep(5 * time.Minute)

		usage, err := disk.Usage(config.CacheDir)
		if err != nil {
			log.Printf("[LRU Cleaner] Failed to get disk usage: %v", err)
			continue
		}

		if usage.UsedPercent > maxPercentage {
			log.Printf("[LRU Cleaner] Disk usage is at %.1f%% (Limit: %.1f%%). Starting cleanup...", usage.UsedPercent, maxPercentage)
			
			targetUsageBytes := uint64(float64(usage.Total) * (maxPercentage / 100.0))
			if usage.Used <= targetUsageBytes {
				continue
			}
			bytesToFree := usage.Used - targetUsageBytes

			items := getCacheItems(config.CacheDir)

			sort.Slice(items, func(i, j int) bool {
				return items[i].MTime.Before(items[j].MTime)
			})

			var freedBytes uint64 = 0
			var deletedCount int = 0

			for _, item := range items {
				if freedBytes >= bytesToFree {
					break
				}

				if err := os.RemoveAll(item.Path); err == nil {
					freedBytes += item.Size
					deletedCount++
					log.Printf("[LRU Cleaner] Evicted %s (Freed %d bytes)", filepath.Base(item.Path), item.Size)
				}
			}

			log.Printf("[LRU Cleaner] Cleanup finished. Deleted %d tracks, freed %.2f MB.", deletedCount, float64(freedBytes)/(1024*1024))
		}
	}
}

func getCacheItems(cacheDir string) []CacheItem {
	var items []CacheItem

	entries, err := os.ReadDir(cacheDir)
	if err != nil {
		return items
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		dirPath := filepath.Join(cacheDir, entry.Name())
		var dirSize uint64
		var lastModified time.Time

		filepath.Walk(dirPath, func(path string, info os.FileInfo, err error) error {
			if err != nil {
				return nil
			}
			if !info.IsDir() {
				dirSize += uint64(info.Size())
				if info.ModTime().After(lastModified) {
					lastModified = info.ModTime()
				}
			}
			return nil
		})

		if dirSize > 0 {
			items = append(items, CacheItem{
				Path:  dirPath,
				Size:  dirSize,
				MTime: lastModified,
			})
		}
	}

	return items
}
