class DMigrate < Formula
  desc "Database-agnostic CLI tool for schema migration and data management"
  homepage "https://github.com/pt9912/d-migrate"
  # Template aligned with the release archive layout; the actual tap formula
  # should be generated for pt9912/homebrew-d-migrate via homebrew-releaser.
  version "0.5.0"
  url "https://github.com/pt9912/d-migrate/releases/download/v#{version}/d-migrate-#{version}.zip"
  sha256 "REPLACE_WITH_RELEASE_ZIP_SHA256"
  license "MIT"

  depends_on "openjdk@21"

  def install
    release_dir = Dir["d-migrate-*"].fetch(0)
    libexec.install Dir["#{release_dir}/*"]
    (bin/"d-migrate").write_env_script(
      libexec/"bin/d-migrate",
      Language::Java.overridable_java_home_env("21"),
    )
  end

  test do
    assert_match "Database-agnostic migration", shell_output("#{bin}/d-migrate --help")
  end
end
