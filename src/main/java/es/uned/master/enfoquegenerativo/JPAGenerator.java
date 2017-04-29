package es.uned.master.enfoquegenerativo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.xml.sax.SAXException;

import es.uned.master.enfoquegenerativo.xml.beans.DomainObjectComplexType;
import es.uned.master.enfoquegenerativo.xml.beans.Jpa;

@SpringBootApplication
public class JPAGenerator implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(JPAGenerator.class);

	private static final String XML_SCHEMA_FILE = "/schema/jpa.xsd";
	private static final String ENTITY_JPA_TEMPLATE = "/templates/entity.vm";
	private static final String REPOSITORY_JPA_TEMPLATE = "/templates/repository.vm";

	public static void main(String args[]) {
		SpringApplication.run(JPAGenerator.class, args);
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		// Evaluación y validación de los argumentos de entrada
		if (args == null || args.getSourceArgs().length == 0) {
			logger.error("Debe proporcionar los argumentos necesarios");
			logger.error("[--d=<Directorio destino>] <Fichero XML>");
			System.exit(0);
		} else if (args.getSourceArgs().length > 2) {
			logger.error("Debe proporcionar el numero de argumentos correcto");
			logger.error("[--d=<Directorio destino>] <Fichero XML>");
			System.exit(0);
		} else if (args.getSourceArgs().length == 1
				&& (args.getNonOptionArgs() == null || args.getNonOptionArgs().isEmpty())) {
			logger.error("Debe proporcionar la ruta al fichero XML");
			logger.error("[--d=<Directorio destino>] <Fichero XML>");
			System.exit(0);
		} else if (args.getSourceArgs().length == 2
				&& ((args.getNonOptionArgs() == null || args.getNonOptionArgs().isEmpty())
				|| (args.getOptionValues("d") == null || args.getOptionValues("d").isEmpty()))) {
			logger.error("Los argumentos proporcionados no son correctos");
			logger.error("[--d=<Directorio destino>] <Fichero XML>");
			System.exit(0);
		}

		String targetDirectory = "";
		if (args.getOptionValues("d") != null && args.getOptionValues("d").get(0) != null) {
			targetDirectory = args.getOptionValues("d").get(0);
			logger.info("Se establece como directorio destino {}", targetDirectory);
		}
		String xmlFilePath = args.getNonOptionArgs().get(0);
		logger.info("Se establece como fichero XML origen {}", xmlFilePath);

		// VALIDADOR
		File xmlFile = new File(xmlFilePath);
		InputStream is = null;
		OutputStream os = null;
		try {
			logger.info("Se procede a validar el XML facilitado contra el XSD predefinido");
			is = getClass().getClassLoader().getResourceAsStream(XML_SCHEMA_FILE);
			byte[] buffer = new byte[is.available()];
			is.read(buffer);
		    File schemaFile = new File("xsdFile.tmp");
		    os = new FileOutputStream(schemaFile);
		    os.write(buffer);
			SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			Schema schema = factory.newSchema(schemaFile);
			Validator validator = schema.newValidator();
			validator.validate(new StreamSource(xmlFile));
		} catch (IOException | SAXException e) {
			logger.error("El fichero XML no está bien construido de acuerdo con el esquema XSD");
			logger.error(e.getMessage());
			System.exit(0);
		} finally {
			if (is!=null){
				is.close();
			}
			if (os!=null){
				os.close();
			}
		}

		// Unmarshalling del XML a clases Java con las que trabajar en el
		// generador
		Jpa jpa = null;
		try {
			JAXBContext jaxbContext = JAXBContext.newInstance(Jpa.class);

			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
			jpa = (Jpa) jaxbUnmarshaller.unmarshal(xmlFile);
		} catch (JAXBException jex) {
			logger.error("Hubo un problema transformando el fichero XML");
			System.exit(0);
		}

		// GENERADOR
		if (jpa != null) {
			String packageName = jpa.getPackageName();
			String packagePath = packageName.replace(".", "\\");
			String fullPath = targetDirectory + "\\" + packagePath;
			File directoriesFile = new File(fullPath);
			if (!directoriesFile.exists()) {
				logger.info("Se cera el directorio {}", fullPath);
				directoriesFile.mkdirs();
			}
			VelocityEngine ve = new VelocityEngine();
			ve.addProperty(VelocityEngine.RESOURCE_LOADER, "classpath");
			ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
			ve.init();
			Template entityTemplate = ve.getTemplate(ENTITY_JPA_TEMPLATE);
			Template repositoryTemplate = ve.getTemplate(REPOSITORY_JPA_TEMPLATE);
			Context context;
			File entityFile;
			File repositoryFile;
			int entityClasses = 0;
			int repositoryClasses = 0;
			for (DomainObjectComplexType domainObject : jpa.getDomainObject()) {
				context = new VelocityContext();
				context.put("packageName", packageName);
				context.put("domainObject", domainObject);
				logger.info("Se procesa el objeto de dominio {}", domainObject.getName());
				if (domainObject.getEntity() != null) {
					entityClasses++;
					BufferedWriter entityWriter = null;
					try {
						String entityPath = fullPath + "\\entity\\" + domainObject.getName() + ".java";
						logger.info("Se crea la clase de entidad correspondiente al objeto de dominio {} en la ruta {}",
								domainObject.getName(), entityPath);
						entityFile = new File(entityPath);
						if (!entityFile.getParentFile().exists()) {
							entityFile.getParentFile().mkdirs();
						}
						entityWriter = new BufferedWriter(new FileWriter(entityFile, false));
						entityTemplate.merge(context, entityWriter);
					} catch (Exception ex) {
						logger.error(
								"Hubo un error generando la clase de entidad correspondiente al objeto de dominio {}",
								domainObject.getName());
					} finally {
						if (entityWriter != null) {
							entityWriter.flush();
							entityWriter.close();
						}
					}

				}
				if (domainObject.getRepository() != null) {
					repositoryClasses++;
					BufferedWriter repositoryWriter = null;
					try {
						String repositoryPath = fullPath + "\\repository\\" + domainObject.getName()
								+ "Repository.java";
						logger.info(
								"Se crea la clase de respositorio correspondiente al objeto de dominio {} en la ruta {}",
								domainObject.getName(), repositoryPath);
						repositoryFile = new File(repositoryPath);
						if (!repositoryFile.getParentFile().exists()) {
							repositoryFile.getParentFile().mkdirs();
						}
						repositoryWriter = new BufferedWriter(new FileWriter(repositoryFile, false));
						repositoryTemplate.merge(context, repositoryWriter);
					} catch (Exception ex) {
						logger.error(
								"Hubo un error generando la clase de respositorio correspondiente al objeto de dominio {}",
								domainObject.getName());
					} finally {
						if (repositoryWriter != null) {
							repositoryWriter.flush();
							repositoryWriter.close();
						}
					}
				}
			}
			logger.info(
					"Se han procesado un total de {} objetos de dominio, "
							+ "con un resultado de {} clases de entidad y {} clases de repositorio",
					jpa.getDomainObject().size(), entityClasses, repositoryClasses);
			logger.info("Fin del proceso de generación de la capa de persistencia JPA");
		}

	}

}
