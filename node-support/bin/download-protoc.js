#!/usr/bin/env node

const request = require("request");
const unzipper = require("unzipper");
const mkdirp = require("mkdirp");
const path = require("path");
const fs = require("fs");
const rimraf = require("rimraf");

const downloadUrlPrefix = "https://github.com/protocolbuffers/protobuf/releases/download/v";
const protocVersion = "3.15.6";
function makeDownloadFile(platformArch) {
  return "protoc-" + protocVersion + "-" + platformArch + ".zip";
}
function determineDownloadFile() {
  switch (process.platform) {
    case "linux":
      switch (process.arch) {
        case "arm64":
          return makeDownloadFile("linux-aarch_64");
        case "ppc64":
          return makeDownloadFile("linux-ppcle_64");
        case "x32":
          return makeDownloadFile("linux-x86_32");
        case "x64":
          return makeDownloadFile("linux-x86_64");
      }
      break;
    case "win32":
      switch (process.arch) {
        case "x32":
          return makeDownloadFile("win32");
        case "x64":
          return makeDownloadFile("win64");
      }
      break;
    case "darwin":
      switch (process.arch) {
        case "x32":
          return makeDownloadFile("osx-x86_32");
        case "x64":
          return makeDownloadFile("osx-x86_64");
      }
      break;
  }
  throw new Error("There is no protoc compiler available for the current platform/arch combination: " + process.platform + "/" + process.arch)
}

const protocDir = path.join(__dirname, "..", "protoc");
const protocBin = path.join(protocDir, "bin", "protoc" + (process.platform === "win32" ? ".exe" : ""));
const downloadFile = determineDownloadFile();

const protocZipFile = path.join(protocDir, downloadFile);
// Check if we already have the file downloaded
if (!fs.existsSync(protocZipFile)) {

  // First, delete the directory if it exists, then recreate
  if (fs.existsSync(protocDir)) {
    rimraf.sync(protocDir);
  }
  mkdirp.sync(path.join(__dirname, "..", "protoc"));

  // Download the file
  const downloadUrl = downloadUrlPrefix + protocVersion + "/" + downloadFile;
  console.log("Downloading protoc from " + downloadUrl);

  const file = fs.createWriteStream(protocZipFile);
  request(downloadUrl)
    .pipe(file)
    .on("finish", () => {
      fs.createReadStream(protocZipFile)
        .pipe(unzipper.Parse())
        .on("entry", function(entry) {
          const extractPath = path.join(protocDir, entry.path);
          const extractDirectory = "Directory" === entry.type ? extractPath : path.dirname(extractPath);

          mkdirp(extractDirectory, function(err) {
            if (err) throw err;
            if ("File" === entry.type) {
              entry.pipe(fs.createWriteStream(extractPath))
                .on("finish", function() {
                  if (protocBin === extractPath) {
                    fs.chmod(extractPath, 0o755, function(err) {
                      if (err) throw err;
                    });
                  }
                });
            }
          });
        });
    });
}
