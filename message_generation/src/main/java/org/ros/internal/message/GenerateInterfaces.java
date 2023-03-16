/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.ros.internal.message;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.commons.io.FileUtils;
import org.ros.exception.RosMessageRuntimeException;
import org.ros.internal.message.definition.MessageDefinitionProviderChain;
import org.ros.internal.message.definition.MessageDefinitionTupleParser;
import org.ros.internal.message.service.ServiceDefinitionFileProvider;
import org.ros.internal.message.topic.TopicDefinitionFileProvider;
import org.ros.message.MessageDeclaration;
import org.ros.message.MessageFactory;
import org.ros.message.MessageIdentifier;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

/**
 * @author damonkohler@google.com (Damon Kohler)
 */
public class GenerateInterfaces {

    private final TopicDefinitionFileProvider topicDefinitionFileProvider = new TopicDefinitionFileProvider();
    private final ServiceDefinitionFileProvider serviceDefinitionFileProvider = new ServiceDefinitionFileProvider();
    private final MessageDefinitionProviderChain messageDefinitionProviderChain = new MessageDefinitionProviderChain();
    private final MessageFactory messageFactory;

    static private final String ROS_PACKAGE_PATH = "ROS_PACKAGE_PATH";

    @Option(name = "-p", aliases = {"--package-path"}, metaVar = "PATH",
            usage = "path to packages, default is env(" + ROS_PACKAGE_PATH + ")")
    private String packagePath = System.getenv(ROS_PACKAGE_PATH);
    @Option(name = "-o", aliases = {"--output-path"}, metaVar = "DIRECTORY", usage = "output path, default is .")
    private File outputPath = new File(".");
    @Option(name = "-n", aliases = {"--package-names"}, handler = StringArrayOptionHandler.class,  
            usage = "names of the packages")
    private String[] packageNames;
    @Option(name = "-s", aliases = {"--sources"}, metaVar = "PATH",
            usage = "source folder on single pkg gen")
    private String sourcesPath;
    @Option(name = "--help", usage = "show help output")
    private boolean help = false;

    public GenerateInterfaces(String[] args) {

        messageDefinitionProviderChain.addMessageDefinitionProvider(topicDefinitionFileProvider);
        messageDefinitionProviderChain.addMessageDefinitionProvider(serviceDefinitionFileProvider);
        messageFactory = new DefaultMessageFactory(messageDefinitionProviderChain);

        CmdLineParser parser = new CmdLineParser(this);

        try {
            parser.setUsageWidth(80);
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.out.println("GenerateInterfaces [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();
            return;
        }

        if (help) {
            System.out.println("GenerateInterfaces [options...] arguments...");
            parser.printUsage(System.out);
            System.out.println();
            return;
        }

        Collection<File> packagePaths = Lists.newArrayList();
        for (String path : packagePath.split(File.pathSeparator)) {
            File packageDirectory = new File(path);
            if (packageDirectory.exists()) {
                packagePaths.add(packageDirectory);
            }
        }

        Collection<File> sourcePaths = Lists.newArrayList();
        for (String path : sourcesPath.split(File.pathSeparator)) {
            File packageDirectory = new File(path);
            if (packageDirectory.exists()) {
                sourcePaths.add(packageDirectory);
            }
        }
        
        List<String> arguments = Lists.newArrayList(packageNames);
        this.generate(outputPath, arguments, packagePaths, sourcePaths);

    }

    /**
     * @param packages a list of packages containing the topic types to generate interfaces for
     * @param outputDirectory the directory to write the generated interfaces to
     * @throws IOException
     */
    private void writeTopicInterfaces(File outputDirectory, Collection<String> packages)
            throws IOException {
        Collection<MessageIdentifier> topicTypes = Sets.newHashSet();

        if (packages.isEmpty()) {
            packages = topicDefinitionFileProvider.getPackages();
            System.out.println("no pkg given, generate all");
        }
        for (String pkg : packages) {
            Collection<MessageIdentifier> messageIdentifiers
                    = topicDefinitionFileProvider.getMessageIdentifiersByPackage(pkg);
            if (messageIdentifiers != null) {
                topicTypes.addAll(messageIdentifiers);
            } 
        }
        for (MessageIdentifier topicType : topicTypes) {
            String definition = messageDefinitionProviderChain.get(topicType.getType());
            MessageDeclaration messageDeclaration = new MessageDeclaration(topicType, definition);
            writeInterface(messageDeclaration, outputDirectory, true);
        }
    }

    /**
     * @param packages a list of packages containing the topic types to generate interfaces for
     * @param outputDirectory the directory to write the generated interfaces to
     * @throws IOException
     */
    private void writeServiceInterfaces(File outputDirectory, Collection<String> packages)
            throws IOException {
        Collection<MessageIdentifier> serviceTypes = Sets.newHashSet();
        if (packages.isEmpty()) {
            packages = serviceDefinitionFileProvider.getPackages();
        }
        for (String pkg : packages) {
            Collection<MessageIdentifier> messageIdentifiers
                    = serviceDefinitionFileProvider.getMessageIdentifiersByPackage(pkg);
            if (messageIdentifiers != null) {
                serviceTypes.addAll(messageIdentifiers);
            } 
        }
        for (MessageIdentifier serviceType : serviceTypes) {
            String definition = messageDefinitionProviderChain.get(serviceType.getType());
            MessageDeclaration serviceDeclaration
                    = MessageDeclaration.of(serviceType.getType(), definition);
            writeInterface(serviceDeclaration, outputDirectory, false);
            List<String> requestAndResponse = MessageDefinitionTupleParser.parse(definition, 2);
            MessageDeclaration requestDeclaration
                    = MessageDeclaration.of(serviceType.getType() + "Request", requestAndResponse.get(0));
            MessageDeclaration responseDeclaration
                    = MessageDeclaration.of(serviceType.getType() + "Response", requestAndResponse.get(1));
            writeInterface(requestDeclaration, outputDirectory, true);
            writeInterface(responseDeclaration, outputDirectory, true);
        }
    }

    private void writeInterface(MessageDeclaration messageDeclaration, File outputDirectory,
            boolean addConstantsAndMethods) {
        MessageInterfaceBuilder builder = new MessageInterfaceBuilder();
        builder.setPackageName(messageDeclaration.getPackage());
        builder.setInterfaceName(messageDeclaration.getName());
        builder.setMessageDeclaration(messageDeclaration);
        builder.setAddConstantsAndMethods(addConstantsAndMethods);
        try {
            String content;
            content = builder.build(messageFactory);
            File file = new File(outputDirectory, messageDeclaration.getType() + ".java");
            FileUtils.writeStringToFile(file, content);
            System.out.println("Generate Interface for " + messageDeclaration.getType());
        } catch (Exception e) {
            System.out.printf("Failed to generate interface for %s.\n", messageDeclaration.getType());
            e.printStackTrace();
        }
    }

    private void generate(File outputDirectory, Collection<String> packages, Collection<File> packagePath, Collection<File> sourcesPath) {
        for (File directory : packagePath) {
            topicDefinitionFileProvider.addDirectory(directory);
            serviceDefinitionFileProvider.addDirectory(directory);
        }

        for (File directory : sourcesPath) {
            topicDefinitionFileProvider.addDirectory(directory);
            serviceDefinitionFileProvider.addDirectory(directory);
        }

        if (packages.size() == 1 && packagePath.size() == 1) {
            String pkg = (String) packages.toArray()[0];
            File pkgp = (File) packagePath.toArray()[0];
            System.out.println("single pkg generate, force pkg to " + pkg + " , with path:" + pkgp.getPath());
            topicDefinitionFileProvider.updateOnePKG(pkg);
            serviceDefinitionFileProvider.updateOnePKG(pkg);

        } else {
            topicDefinitionFileProvider.update();
            serviceDefinitionFileProvider.update();
        }
        
        for(String pkg : packages) {
            boolean empty = topicDefinitionFileProvider.getMessageIdentifiersByPackage(pkg).isEmpty();
            empty &= serviceDefinitionFileProvider.getMessageIdentifiersByPackage(pkg).isEmpty();
            if(empty) {
                System.err.println("No Interfaces found for pkg: " + pkg);
                //serviceDefinitionFileProvider.addDirectory();
            }
        }

        try {
            writeTopicInterfaces(outputDirectory, packages);
            writeServiceInterfaces(outputDirectory, packages);
        } catch (IOException e) {
            throw new RosMessageRuntimeException(e);
        }
    }

    public static void main(String[] args) {
	System.out.println("Generate Interfaces v2");
        GenerateInterfaces generateInterfaces = new GenerateInterfaces(args);
    }
}
