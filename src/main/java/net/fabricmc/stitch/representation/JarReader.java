/*
 * Copyright (c) 2016, 2017, 2018 Adrian Siekierka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.representation;

import net.fabricmc.stitch.util.Pair;
import net.fabricmc.stitch.util.StitchUtil;
import org.objectweb.asm.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.jar.JarInputStream;

public class JarReader {
    private final JarEntry jar;

    public JarReader(JarEntry jar) {
        this.jar = jar;
    }

    private class VisitorClass extends ClassVisitor {
        private ClassEntry entry;

        public VisitorClass(int api, ClassVisitor classVisitor) {
            super(api, classVisitor);
        }

        @Override
        public void visit(final int version, final int access, final String name, final String signature,
                          final String superName, final String[] interfaces) {
            this.entry = jar.getClass(name, true);
            this.entry.populate(access, signature, superName, interfaces);

            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(final int access, final String name, final String descriptor,
                                       final String signature, final Object value) {
            FieldEntry field = new FieldEntry(access, name, descriptor, signature);
            this.entry.fields.put(field.getKey(), field);

            return new VisitorField(api, super.visitField(access, name, descriptor, signature, value),
                    entry, field);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                         final String signature, final String[] exceptions) {
            MethodEntry method = new MethodEntry(access, name, descriptor, signature);
            this.entry.methods.put(method.getKey(), method);

            return new VisitorMethod(api, super.visitMethod(access, name, descriptor, signature, exceptions),
                    entry, method);
        }
    }

    private class VisitorField extends FieldVisitor {
        private final ClassEntry classEntry;
        private final FieldEntry entry;

        public VisitorField(int api, FieldVisitor fieldVisitor, ClassEntry classEntry, FieldEntry entry) {
            super(api, fieldVisitor);
            this.classEntry = classEntry;
            this.entry = entry;
        }
    }

    private class VisitorMethod extends MethodVisitor {
        private final ClassEntry classEntry;
        private final MethodEntry entry;

        public VisitorMethod(int api, MethodVisitor methodVisitor, ClassEntry classEntry, MethodEntry entry) {
            super(api, methodVisitor);
            this.classEntry = classEntry;
            this.entry = entry;
        }
    }

    public void apply() throws IOException {
        // Stage 1: read .JAR
        try (FileInputStream fileStream = new FileInputStream(jar.file)) {
            try (JarInputStream jarStream = new JarInputStream(fileStream)) {
                java.util.jar.JarEntry entry;

                while ((entry = jarStream.getNextJarEntry()) != null) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }

                    ClassReader reader = new ClassReader(jarStream);
                    reader.accept(new VisitorClass(Opcodes.ASM7, null), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }

        System.err.println("Read " + this.jar.getAllClasses().size() + " (" + this.jar.getClasses().size() + ") classes.");

        // Stage 2: find subclasses
        this.jar.getAllClasses().forEach((c) -> c.populateParents(jar));
        System.err.println("Populated subclass entries.");

        // Stage 3: join identical MethodEntries
        System.err.println("Joining MethodEntries...");
        Set<ClassEntry> traversedClasses = StitchUtil.newIdentityHashSet();

        int joinedMethods = 1;
        int uniqueMethods = 0;

        Collection<MethodEntry> checkedMethods = StitchUtil.newIdentityHashSet();

        for (ClassEntry entry : jar.getAllClasses()) {
            if (traversedClasses.contains(entry)) {
                continue;
            }

            ClassPropagationTree tree = new ClassPropagationTree(jar, entry);
            if (tree.getClasses().size() == 1) {
                traversedClasses.add(entry);
                continue;
            }

            for (ClassEntry c : tree.getClasses()) {
                for (MethodEntry m : c.getMethods()) {
                    if (!checkedMethods.add(m)) {
                        continue;
                    }

                    // get all matching entries
                    List<ClassEntry> mList = m.getMatchingEntries(jar, c);
                    if (mList.size() > 1) {
                        for (int i = 0; i < mList.size(); i++) {
                            ClassEntry key = mList.get(i);
                            MethodEntry value = key.getMethod(m.getKey());
                            if (value != m) {
                                key.methods.put(m.getKey(), m);
                                joinedMethods++;
                            }
                        }
                    }
                }
            }

            traversedClasses.addAll(tree.getClasses());
        }

        System.err.println("Joined " + joinedMethods + " MethodEntries (" + uniqueMethods + " unique, " + traversedClasses.size() + " classes).");
    }
}