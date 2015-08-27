/*
 * Copyright 2008 Codehaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.mojo.nsis;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.mojo.nsis.Compression.Type;
import org.codehaus.mojo.nsis.io.ProcessOutputConsumer;
import org.codehaus.mojo.nsis.io.ProcessOutputHandler;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.io.InputStreamFacade;
import org.codehaus.plexus.util.io.RawInputStreamFacade;

/**
 * Compile the <code>setup.nsi</code> into an installer executable.
 * 
 * @author <a href="mailto:joakime@apache.org">Joakim Erdfelt</a>
 */
@Mojo( name = "make", defaultPhase = LifecyclePhase.PACKAGE )
public class MakeMojo
    extends AbstractMojo
    implements ProcessOutputConsumer
{
	private static final String LINE_SEPARATOR = "\r\n";
	
	/**
	 * Indicates if the execution should be disabled. If true, nothing will
	 * occur during execution.
	 */
	@Parameter( property = "nsis.disabled", defaultValue = "false" )
	private boolean disabled;
	
	/**
	 * Attach Artifact Flag - can generate non installer artifact, such as an exe, that should
	 * not be attached.
	 */
	@Parameter( property = "nsis.setup.attachArtifact", defaultValue = "true" )
	private boolean attachArtifact;
	
    /**
     * The binary to execute for makensis. Default assumes that the makensis can be found in the path.
     */
    @Parameter( property = "nsis.makensis.bin", defaultValue = "makensis", required = true )
    private String makensisBin;


    /**
     * Compression settings
     */
    @Parameter( required = false )
    private Compression compression;
    
    /**
     * The main setup script.
     */
    @Parameter( property = "nsis.scriptfile", defaultValue = "setup.nsi", required = true )
    private String scriptFile;

    /**
     * The generated installer exe output file.
     */
    @Parameter( property = "nsis.output.file", defaultValue = "${project.build.finalName}.exe", required = true )
    private String outputFile;

    /**
     * The Maven project itself.
     */
    @Parameter( defaultValue = "${project}", required = true, readonly = true )
    private MavenProject project;

    /**
     * A map of environment variables which will be passed to the execution of <code>makensis</code>
     */
    @Parameter
    private Map<String, String> environmentVariables = new HashMap<String, String>();
    
    @Parameter
    private String classifier;
    
    /**
     * Internal project helper component.
     */
    @Component
    private MavenProjectHelper projectHelper;

    private boolean isWindows;

    public MakeMojo()
    {
        isWindows = ( System.getProperty( "os.name" ).startsWith( "Windows" ) );
    }

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
    	if (disabled) {
			getLog().info("MOJO is disabled. Doing nothing.");
			return;
		}
    	
        validate();
        List<String> commands = new ArrayList<String>();
        commands.add( makensisBin ); // The makensis binary

        File buildDirectory =  new File( project.getBuild().getDirectory() );
        File targetFile = getOutputFile( buildDirectory, outputFile, classifier );

        File targetDirectory = targetFile.getParentFile();

        // be sure the target directory exists
        if ( !targetDirectory.exists() )
        {
            try
            {
                FileUtils.forceMkdir( targetDirectory );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Can't create target directory " + targetDirectory.getAbsolutePath(),
                                                  e );
            }
        }

        String optPrefix = ( isWindows ) ? "/" : "-";

        // The installer output file
        commands.add( optPrefix + "X" + "OutFile " + StringUtils.quoteAndEscape( targetFile.getAbsolutePath(), '\'' ) );
        
        commands.add( optPrefix + "V2" ); // Verboseness Level
        
        File actualScriptFile = processInputFile(buildDirectory);
        
        getLog().debug("Processing Script file: " + actualScriptFile.getAbsolutePath());
        commands.add( actualScriptFile.getAbsolutePath() ); // The setup script file

        ProcessBuilder builder = new ProcessBuilder( commands );
        builder.directory( project.getBasedir() ); // The working directory
        builder.redirectErrorStream( true );
        if ( environmentVariables != null )
        {
            builder.environment().putAll( environmentVariables );
        }

        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "directory:  " + builder.directory().getAbsolutePath() );
            getLog().debug( "commands  " + builder.command().toString() );
            if ( builder.environment() != null )
            {
                getLog().debug( "environment variables: ");
                for( Map.Entry<String, String> entry : builder.environment().entrySet() )
                {
                    getLog().debug( "  " + entry.getKey() + ": " + entry.getValue() );
                }
            }
        }
        
        try
        {
        	long start = System.currentTimeMillis();
            Process process = builder.start();
            ProcessOutputHandler output = new ProcessOutputHandler( process.getInputStream(), this );
            output.startThread();

            int status;
            try
            {
                status = process.waitFor();
            }
            catch ( InterruptedException e )
            {
                status = process.exitValue();
            }

            output.setDone( true );

            if ( status != 0 )
            {
                throw new MojoExecutionException(
                                                  "Execution of makensis compiler failed. See output above for details." );
            }

            long end = System.currentTimeMillis();
            
            consumeOutputLine("Execution completed in " + (end - start) + "ms");
            
            if (attachArtifact) {
            	// Attach the exe to the install tasks.
            	projectHelper.attachArtifact( project, "exe", classifier, targetFile );
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Unable to execute makensis", e );
        }
    }

    public void consumeOutputLine( String line )
    {
        getLog().info( "[MAKENSIS] " + line );
    }

    private void validate()
        throws MojoFailureException
    {
        // check if the setup-file contains the property 'OutFile'
        // this will write the outputFile relative to the setupScript, no matter if it's configured otherwise in the pom
        BufferedReader reader = null;
        try
        {
            reader = new BufferedReader( new FileReader( scriptFile ) );
            for ( String line = reader.readLine(); line != null; line = reader.readLine() )
            {
                if ( line.trim().startsWith( "OutFile " ) )
                {
                    getLog().warn( "setupScript contains the property 'OutFile'. "
                                       + "Please move this setting to the plugin-configuration" );
                }
            }
        }
        catch ( IOException e )
        {
            // we can't find and/or read the file, but let nsis throw an exception
        }
        finally
        {
            IOUtil.close( reader );
        }
    }
    
    private File processInputFile(File basedir) throws MojoExecutionException {
    	try
        {
	    	File scriptFileFile = new File(scriptFile);
	    	File file = new File(basedir, scriptFileFile.getName());
	    	
	    	// ignore setting for 
	    	if (compression != null && !compression.isDefault()) {
	    		String contents = FileUtils.fileRead(scriptFileFile);
	    		StringBuffer buf = new StringBuffer();
	    		buf.append("SetCompressor " + compression.getType().name());
	    		if (compression.isDoFinal()) {
	    			buf.append(" /FINAL");
	    		}
	    		if (compression.isDoSolid()) {
	    			buf.append(" /SOLID");
	    		}
	    		buf.append(LINE_SEPARATOR);
	    		buf.append("SetCompressorDictSize " + compression.getDictionarySize());
	    		buf.append(LINE_SEPARATOR);
	    		
	    		buf.append(LINE_SEPARATOR);
	    		buf.append(contents);
	    		InputStreamFacade is = new RawInputStreamFacade(new ByteArrayInputStream(buf.toString().getBytes("UTF-8")));
	    		FileUtils.copyStreamToFile(is, file);
	    	} else {
	    		FileUtils.copyFile(scriptFileFile, file);
	    	}
	    	return file;
        } catch (IOException e) {
        	throw new MojoExecutionException( "Unable to copy file", e );
        }
    }
    
    protected static File getOutputFile( File basedir, String finalName, String classifier )
    {
        if ( classifier == null )
        {
            classifier = "";
        }
        else if ( classifier.trim().length() > 0 && !classifier.startsWith( "-" ) )
        {
            classifier = "-" + classifier;
        }
        
        int extensionIndex = finalName.lastIndexOf( '.' );

        return new File( basedir, finalName.substring( 0, extensionIndex ) + classifier + finalName.substring( extensionIndex ) );
    }

}
