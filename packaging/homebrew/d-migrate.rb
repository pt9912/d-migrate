class DMigrate < Formula
  desc "Database-agnostic CLI tool for schema migration and data management"
  homepage "https://github.com/pt9912/d-migrate"
  # Template aligned with the release archive layout; the actual tap formula
  # should be generated for pt9912/homebrew-d-migrate via homebrew-releaser.
  version "0.9.11"
  url "https://github.com/pt9912/d-migrate/releases/download/v#{version}/d-migrate-#{version}.zip"
  sha256 "f68089131d6a56631a6dc5a15a3702f08b9921aeecb9c972932ddb6a94ebbaab"
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
