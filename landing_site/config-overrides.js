const { override, addWebpackModuleRule } = require("customize-cra")

module.exports = override(
  addWebpackModuleRule({
    test: /\.md$/,
    use: "raw-loader",
  })
)