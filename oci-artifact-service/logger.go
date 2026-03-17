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

	var logger *zap.Logger
	var err error

	if logType == "json" {
		config := zap.NewProductionConfig()
		config.EncoderConfig.TimeKey = "timestamp"
		config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
		logger, err = config.Build()
	} else {
		logger, err = zap.NewDevelopment()
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
