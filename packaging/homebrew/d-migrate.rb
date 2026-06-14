class DMigrate < Formula
  desc "Database-agnostic CLI tool for schema migration and data management"
  homepage "https://github.com/pt9912/d-migrate"
  # Template aligned with the release archive layout; the actual tap formula
  # should be generated for pt9912/homebrew-d-migrate via homebrew-releaser.
  version "0.9.7"
  url "https://github.com/pt9912/d-migrate/releases/download/v#{version}/d-migrate-#{version}.zip"
  sha256 "8a67d9b49728702b4441a2966a1477fb7c1afec94bcd1b49165088cc7ffb46b1"
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
