package main

import (
	"os"

	"go.uber.org/zap"
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
		logger, err = zap.NewProduction()
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
