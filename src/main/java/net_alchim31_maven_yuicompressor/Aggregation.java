package net_alchim31_maven_yuicompressor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.IOUtil;
import org.sonatype.plexus.build.incremental.BuildContext;

public class Aggregation {
    public File inputDir;
    public File output;
    public String[] includes;
    public String[] excludes;
    public boolean removeIncluded = false;
    public boolean insertNewLine = false;
    public boolean insertFileHeader = false;
    public boolean fixLastSemicolon = false;
    public boolean autoExcludeWildcards = false;
    public boolean moduleOrdering = false;

    public List<File> run(Collection<File> previouslyIncludedFiles, BuildContext buildContext) throws Exception {
        defineInputDir();

        List<File> files;
        if (autoExcludeWildcards) {
            files = getIncludedFiles(previouslyIncludedFiles,buildContext);
        } else {
            files = getIncludedFiles(null,buildContext);
        }

        if (files.size() != 0) {
            if (moduleOrdering) files = orderModules(files);
            output = output.getCanonicalFile();
            output.getParentFile().mkdirs();
            OutputStream out = buildContext.newFileOutputStream( output );
            try {
                for (File file : files) {
                    if (file.getCanonicalPath().equals(output.getCanonicalPath())) {
                        continue;
                    }
                    FileInputStream in = new FileInputStream(file);
                    try {
                        if (insertFileHeader) {
                            out.write(createFileHeader(file).getBytes());
                        }
                        IOUtil.copy(in, out);
                        if (fixLastSemicolon) {
                            out.write(';');
                        }
                        if (insertNewLine) {
                            out.write('\n');
                        }
                    } finally {
                        IOUtil.close(in);
                        in = null;
                    }
                    if (removeIncluded) {
                        file.delete();
                        buildContext.refresh(file);
                    }
                }
            } finally {
                IOUtil.close(out);
                out = null;
            }
        }
        return files;
    }

    private String createFileHeader(File file) {
        StringBuilder header = new StringBuilder();
        header.append("/*");
        header.append(file.getName());
        header.append("*/");

        if (insertNewLine) {
            header.append('\n');
        }

        return header.toString();
    }

    private void defineInputDir() throws Exception {
      if (inputDir == null) {
        inputDir = output.getParentFile();
      }
      inputDir = inputDir.getCanonicalFile();
    }

    private List<File> getIncludedFiles(Collection<File> previouslyIncludedFiles, BuildContext buildContext) throws Exception {
        List<File> filesToAggregate = new ArrayList<File>();
        if (includes != null) {
            for (String include : includes) {
                addInto(include, filesToAggregate, previouslyIncludedFiles);
            }
        }

        //If build is incremental with no delta, then don't include for aggregation
        if(buildContext.isIncremental() && !buildContext.hasDelta(filesToAggregate)){
        	return new ArrayList<File>();
        } else{
        	return filesToAggregate;
        }

    }

    private void addInto(String include, List<File> includedFiles, Collection<File> previouslyIncludedFiles) throws Exception {
        if (include.indexOf('*') > -1) {
            DirectoryScanner scanner = newScanner();
            scanner.setIncludes(new String[] { include });
            scanner.scan();
            String[] rpaths = scanner.getIncludedFiles();
            Arrays.sort(rpaths);
            for (String rpath : rpaths) {
                File file = new File(scanner.getBasedir(), rpath);
                if (!includedFiles.contains(file) && (previouslyIncludedFiles == null || !previouslyIncludedFiles.contains(file))) {
                    includedFiles.add(file);
                }
            }
        } else {
            File file = new File(include);
            if (!file.isAbsolute()) {
                file = new File(inputDir, include);
            }
            if (!includedFiles.contains(file)) {
                includedFiles.add(file);
            }
        }
    }

    private DirectoryScanner newScanner() throws Exception {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(inputDir);
        if ((excludes != null) && (excludes.length != 0)) {
            scanner.setExcludes(excludes);
        }
        scanner.addDefaultExcludes();
        return scanner;
    }

    private List<File> orderModules(List<File> files) throws Exception {
        int count = files.size();
        List<Module> modules = new ArrayList<Module>(count);
        List<File> result = new ArrayList<File>(count);

        for (File file : files) {
            Module module = new Module(file);

            module.parse();
            modules.add(module);
        }

        for (int i = 0; i < count; i++) {
            Module module;
            boolean redo;

            do {
                module = modules.get(i);
                redo = false;

                for (int j = i + 1; j < count; j++) {
                    Module other = modules.get(j);

                    if (module.requires.contains(other.module)) {
                        modules.set(i, other);
                        modules.set(j, module);
                        redo = true;
                        break;
                    }
                }
            } while (redo);

            result.add(module.file);
        }

        return result;
    }

    private static class Module {
        private File file;
        private String module;
        private Set<String> requires = new HashSet<String>();
        private boolean parsed = false;

        private Module(File file) throws Exception {
            this.file = file;
        }

        private void parse() throws Exception {
            FileInputStream in = new FileInputStream(file);
            InputStreamReader isr = new InputStreamReader(in);
            BufferedReader br = new BufferedReader(isr);
            String text = br.readLine();

            br.close();
            isr.close();
            in.close();

            parsed = parseAmd(text);
        }

        private static final Pattern amdPattern = Pattern.compile(
            "define\\(\"(.*?)\",(?:\\[(.*?)\\],)?.*");

        private boolean parseAmd(String text) throws Exception {
            Matcher matcher = amdPattern.matcher(text);

            if (!matcher.matches()) return false;
            module = matcher.group(1);

            String reqs = matcher.group(2);

            if (reqs == null) return true;

            for (String require : reqs.split(",")) {
                requires.add(require.substring(1, require.length() - 1));
            }

            return true;
        }
    }
}
