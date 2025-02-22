package stirling.software.SPDF.controller.api.other;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import stirling.software.SPDF.utils.ProcessExecutor;
import stirling.software.SPDF.utils.ProcessExecutor.ProcessExecutorResult;
import stirling.software.SPDF.utils.WebResponseUtils;

@RestController
@Tag(name = "Other", description = "Other APIs")
public class OCRController {

    private static final Logger logger = LoggerFactory.getLogger(OCRController.class);

    public List<String> getAvailableTesseractLanguages() {
        String tessdataDir = "/usr/share/tesseract-ocr/4.00/tessdata";
        File[] files = new File(tessdataDir).listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(files).filter(file -> file.getName().endsWith(".traineddata")).map(file -> file.getName().replace(".traineddata", ""))
                .filter(lang -> !lang.equalsIgnoreCase("osd")).collect(Collectors.toList());
    }

    @PostMapping(consumes = "multipart/form-data", value = "/ocr-pdf")
    @Operation(summary = "Process a PDF file with OCR",
            description = "This endpoint processes a PDF file using OCR (Optical Character Recognition). Users can specify languages, sidecar, deskew, clean, cleanFinal, ocrType, ocrRenderType, and removeImagesAfter options. Input:PDF Output:PDF Type:SI-Conditional")
    public ResponseEntity<byte[]> processPdfWithOCR(
            @RequestPart(required = true, value = "fileInput")
            @Parameter(description = "The input PDF file to be processed with OCR")
                    MultipartFile inputFile,
            @RequestParam("languages")
            @Parameter(description = "List of languages to use in OCR processing")
                    List<String> selectedLanguages,
            @RequestParam(name = "sidecar", required = false)
            @Parameter(description = "Include OCR text in a sidecar text file if set to true")
                    Boolean sidecar,
            @RequestParam(name = "deskew", required = false)
            @Parameter(description = "Deskew the input file if set to true")
                    Boolean deskew,
            @RequestParam(name = "clean", required = false)
            @Parameter(description = "Clean the input file if set to true")
                    Boolean clean,
            @RequestParam(name = "clean-final", required = false)
            @Parameter(description = "Clean the final output if set to true")
                    Boolean cleanFinal,
            @RequestParam(name = "ocrType", required = false)
            @Parameter(description = "Specify the OCR type, e.g., 'skip-text', 'force-ocr', or 'Normal'", schema = @Schema(allowableValues = {"skip-text", "force-ocr", "Normal"}))
                    String ocrType,
            @RequestParam(name = "ocrRenderType", required = false, defaultValue = "hocr")
            @Parameter(description = "Specify the OCR render type, either 'hocr' or 'sandwich'", schema = @Schema(allowableValues = {"hocr", "sandwich"}))
                    String ocrRenderType,
            @RequestParam(name = "removeImagesAfter", required = false)
            @Parameter(description = "Remove images from the output PDF if set to true")
                    Boolean removeImagesAfter) throws IOException, InterruptedException {

        // --output-type pdfa
        if (selectedLanguages == null || selectedLanguages.isEmpty()) {
            throw new IOException("Please select at least one language.");
        }
        
        if(!ocrRenderType.equals("hocr") && !ocrRenderType.equals("sandwich")) {
            throw new IOException("ocrRenderType wrong");
        }
        
        // Get available Tesseract languages
        List<String> availableLanguages = getAvailableTesseractLanguages();

        // Validate selected languages
        selectedLanguages = selectedLanguages.stream().filter(availableLanguages::contains).toList();

        if (selectedLanguages.isEmpty()) {
            throw new IOException("None of the selected languages are valid.");
        }
        // Save the uploaded file to a temporary location
        Path tempInputFile = Files.createTempFile("input_", ".pdf");
        Files.copy(inputFile.getInputStream(), tempInputFile, StandardCopyOption.REPLACE_EXISTING);

        // Prepare the output file path
        Path tempOutputFile = Files.createTempFile("output_", ".pdf");

        // Prepare the output file path
        Path sidecarTextPath = null;

        // Run OCR Command
        String languageOption = String.join("+", selectedLanguages);

        
        List<String> command = new ArrayList<>(Arrays.asList("ocrmypdf", "--verbose", "2", "--output-type", "pdf", "--pdf-renderer" , ocrRenderType));

        if (sidecar != null && sidecar) {
            sidecarTextPath = Files.createTempFile("sidecar", ".txt");
            command.add("--sidecar");
            command.add(sidecarTextPath.toString());
        }

        if (deskew != null && deskew) {
            command.add("--deskew");
        }
        if (clean != null && clean) {
            command.add("--clean");
        }
        if (cleanFinal != null && cleanFinal) {
            command.add("--clean-final");
        }
        if (ocrType != null && !ocrType.equals("")) {
            if ("skip-text".equals(ocrType)) {
                command.add("--skip-text");
            } else if ("force-ocr".equals(ocrType)) {
                command.add("--force-ocr");
            } else if ("Normal".equals(ocrType)) {

            }
        }

        command.addAll(Arrays.asList("--language", languageOption, tempInputFile.toString(), tempOutputFile.toString()));

        // Run CLI command
        ProcessExecutorResult result = ProcessExecutor.getInstance(ProcessExecutor.Processes.OCR_MY_PDF).runCommandWithOutputHandling(command);
        if(result.getRc() != 0 && result.getMessages().contains("multiprocessing/synchronize.py") && result.getMessages().contains("OSError: [Errno 38] Function not implemented")) {
        	command.add("--jobs");
        	command.add("1");
        	result = ProcessExecutor.getInstance(ProcessExecutor.Processes.OCR_MY_PDF).runCommandWithOutputHandling(command);
        }
        

        
        
        // Remove images from the OCR processed PDF if the flag is set to true
        if (removeImagesAfter != null && removeImagesAfter) {
            Path tempPdfWithoutImages = Files.createTempFile("output_", "_no_images.pdf");

            List<String> gsCommand = Arrays.asList("gs", "-sDEVICE=pdfwrite", "-dFILTERIMAGE", "-o", tempPdfWithoutImages.toString(), tempOutputFile.toString());

            ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT).runCommandWithOutputHandling(gsCommand);
            tempOutputFile = tempPdfWithoutImages;
        }
        // Read the OCR processed PDF file
        byte[] pdfBytes = Files.readAllBytes(tempOutputFile);
        // Clean up the temporary files
        Files.delete(tempInputFile);
        
        // Return the OCR processed PDF as a response
        String outputFilename = inputFile.getOriginalFilename().replaceFirst("[.][^.]+$", "") + "_OCR.pdf";

        if (sidecar != null && sidecar) {
            // Create a zip file containing both the PDF and the text file
            String outputZipFilename = inputFile.getOriginalFilename().replaceFirst("[.][^.]+$", "") + "_OCR.zip";
            Path tempZipFile = Files.createTempFile("output_", ".zip");

            try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tempZipFile.toFile()))) {
                // Add PDF file to the zip
                ZipEntry pdfEntry = new ZipEntry(outputFilename);
                zipOut.putNextEntry(pdfEntry);
                Files.copy(tempOutputFile, zipOut);
                zipOut.closeEntry();

                // Add text file to the zip
                ZipEntry txtEntry = new ZipEntry(outputFilename.replace(".pdf", ".txt"));
                zipOut.putNextEntry(txtEntry);
                Files.copy(sidecarTextPath, zipOut);
                zipOut.closeEntry();
            }

            byte[] zipBytes = Files.readAllBytes(tempZipFile);

            // Clean up the temporary zip file
            Files.delete(tempZipFile);
            Files.delete(tempOutputFile);
            Files.delete(sidecarTextPath);

            // Return the zip file containing both the PDF and the text file
            return WebResponseUtils.bytesToWebResponse(zipBytes, outputZipFilename, MediaType.APPLICATION_OCTET_STREAM);
        } else {
            // Return the OCR processed PDF as a response
            Files.delete(tempOutputFile);
            return WebResponseUtils.bytesToWebResponse(pdfBytes, outputFilename);
        }

    }

}
