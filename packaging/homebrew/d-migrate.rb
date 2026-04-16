class DMigrate < Formula
  desc "Database-agnostic CLI tool for schema migration and data management"
  homepage "https://github.com/pt9912/d-migrate"
  # Template aligned with the release archive layout; the actual tap formula
  # should be generated for pt9912/homebrew-d-migrate via homebrew-releaser.
  version "0.8.0"
  url "https://github.com/pt9912/d-migrate/releases/download/v#{version}/d-migrate-#{version}.zip"
  sha256 "311bb65213efd40c259edb584f98380c0c6a75cd09f5f3d691f874da59d958cb"
  license "MIT"

  depends_on "openjdk@21"

  def install
    # Homebrew strips the single top-level directory on unpack, so the
    # archive's `d-migrate-X.Y.Z/bin` and `lib/` land directly in cwd.
    # We install everything into libexec and put a launcher in bin/.
    libexec.install Dir["*"]
    (bin/"d-migrate").write_env_script(
      libexec/"bin/d-migrate",
      Language::Java.overridable_java_home_env("21"),
    )
  end

  test do
    assert_match "Database-agnostic migration", shell_output("#{bin}/d-migrate --help")
  end
end
