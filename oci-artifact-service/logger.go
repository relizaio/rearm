package main

import (
	"os"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var sugar *zap.SugaredLogger

func initLogger() {
	logType := os.Getenv("LOG_TYPE")
	if logType == "" {
		logType = "plain"
	}

	// LOG_LEVEL env var controls log level: debug, info (default), warn, error, dpanic, panic, fatal
	logLevel := os.Getenv("LOG_LEVEL")
	if logLevel == "" {
		logLevel = "info"
	}

	var logger *zap.Logger
	var err error

	if logType == "json" {
		config := zap.NewProductionConfig()
		config.EncoderConfig.TimeKey = "timestamp"
		config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

		// Parse and set log level
		level, err := zapcore.ParseLevel(logLevel)
		if err != nil {
			level = zapcore.InfoLevel
		}
		config.Level = zap.NewAtomicLevelAt(level)

		logger, err = config.Build()
	} else {
		config := zap.NewDevelopmentConfig()

		// Parse and set log level
		level, err := zapcore.ParseLevel(logLevel)
		if err != nil {
			level = zapcore.InfoLevel
		}
		config.Level = zap.NewAtomicLevelAt(level)

		logger, err = config.Build()
	}

	if err != nil {
		panic("failed to initialize logger: " + err.Error())
	}

	sugar = logger.Sugar()
}

func getLogger() *zap.SugaredLogger {
	if sugar == nil {
		initLogger()
	}
	return sugar
}
