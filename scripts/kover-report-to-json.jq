def as_array:
  if . == null then []
  elif type == "array" then .
  else [.] end;

def to_counter_object:
  {
    missed: (."+@missed" | tonumber? // 0),
    covered: (."+@covered" | tonumber? // 0)
  };

def to_counters:
  (.counter | as_array
    | map({
        key: ."+@type",
        value: to_counter_object
      })
    | from_entries);

def to_methods:
  (.method | as_array
    | map({
        name: ."+@name",
        desc: ."+@desc",
        line: (."+@line" | tonumber?),
        counters: to_counters
      })
    | sort_by(.name));

def to_classes:
  (.class | as_array
    | map({
        name: ."+@name",
        sourceFile: ."+@sourcefilename",
        methods: to_methods,
        counters: to_counters
      })
    | sort_by(.name));

def to_source_files:
  (.sourcefile | as_array
    | map({
        name: ."+@name",
        counters: to_counters
      })
    | sort_by(.name));

{
  report: {
    name: (.report."+@name" // ""),
    counters: (.report | to_counters),
    packages: (
      .report.package
      | as_array
      | map({
          name: ."+@name",
          counters: to_counters,
          classes: to_classes,
          sourceFiles: to_source_files
        })
      | sort_by(.name)
    )
  }
}
