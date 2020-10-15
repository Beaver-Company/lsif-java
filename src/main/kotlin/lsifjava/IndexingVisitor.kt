@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
package lsifjava

import com.sun.source.tree.*
import com.sun.source.util.*
import com.sun.tools.javac.code.Flags
import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.code.Type
import com.sun.tools.javac.tree.JCTree
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern
import javax.lang.model.element.Element

data class ReferenceData(val useRange: Range, val refRange: Range, val defPath: Path)

class IndexingVisitor(
        task: JavacTask,
        private val compUnit: CompilationUnitTree,
        private val indexer: DocumentIndexer,
        private val indexers: Map<Path, DocumentIndexer>
): TreePathScanner<Unit?, Unit?>() {
    private val trees: Trees = Trees.instance(task)
    private val docs: DocTrees = DocTrees.instance(task)

    // TODO(nsc) handle 'var'
    override fun visitVariable(node: VariableTree, p: Unit?): Unit? {
        // emit var definition
        val defRange = findLocation(currentPath, node.name.toString())?.range ?: return super.visitVariable(node, p)

        indexer.emitDefinition(defRange, node.toString(), docs.getDocComment(currentPath))
        
        return super.visitVariable(node, p)
    }

    override fun visitClass(node: ClassTree, p: Unit?): Unit? {
        val packagePrefix = compUnit.packageName?.toString()?.plus(".") ?: ""
        
        val classOrEnum = if ((node as JCTree.JCClassDecl).sym.flags() and Flags.ENUM.toLong() != 0L) "enum " else "class "

        val range = findLocation(currentPath, node.simpleName.toString())?.range ?: return super.visitClass(node, p)

        indexer.emitDefinition(
            range,
            node.modifiers.toString() + classOrEnum + packagePrefix + node.simpleName,
            docs.getDocComment(currentPath)
        )

        return super.visitClass(node, p)
    }

    override fun visitMethod(node: MethodTree, p: Unit?): Unit? {
        if((node as JCTree.JCMethodDecl).mods.flags and Flags.GENERATEDCONSTR != 0L) return null
        
        val methodName = node.name.toString().let {
            if(it == "<init>") node.sym.owner.name.toString() else it
        }
        
        val returnType = node.returnType?.toString()?.plus(" ") ?: ""
        
        val range = findLocation(currentPath, methodName)?.range ?: return super.visitMethod(node, p)

        indexer.emitDefinition(
            range,
            node.modifiers.toString() +
                returnType +
                methodName + "(" +
                node.parameters.toString(", ") + ")",
            docs.getDocComment(currentPath)
        )
        
        return super.visitMethod(node, p)
    }

    override fun visitNewClass(node: NewClassTree, p: Unit?): Unit? {
        // ignore auto-genned constructors (for now)
        if((node as JCTree.JCNewClass).constructor.flags() and Flags.GENERATEDCONSTR != 0L) return null
        
        val ident = when(node.identifier) {
            is JCTree.JCIdent -> node.identifier
            is JCTree.JCTypeApply -> (node.identifier as JCTree.JCTypeApply).clazz
            else  -> {
                println("identifier was neither JCIdent nor JCTypeApply, but ${node.identifier.javaClass}")
                return super.visitNewClass(node, p)
            }
        } as JCTree.JCIdent
        
        val identPath = TreePath(currentPath, node.identifier)
        val identElement = node.constructor
        
        val defContainer = LanguageUtils.getTopLevelClass(identElement) as Symbol.ClassSymbol? ?: return super.visitNewClass(node, p)
        val (useRange, refRange, defPath) = findReference(identElement, ident.name.toString(), defContainer, identPath) ?: return super.visitNewClass(node, p)

        indexer.emitUse(useRange, refRange, defPath)

        return super.visitNewClass(node, p)
    }

    override fun visitMethodInvocation(node: MethodInvocationTree?, p: Unit?): Unit? {
        val symbol = when((node as JCTree.JCMethodInvocation).meth) {
            is JCTree.JCFieldAccess -> (node.meth as JCTree.JCFieldAccess).sym
            is JCTree.JCIdent -> (node.meth as JCTree.JCIdent).sym
            else -> {
                println("method receiver tree was neither JCFieldAccess nor JCIdent but ${node.meth.javaClass}")
                return super.visitMethodInvocation(node, p)
            }
        }
        
        //val isStatic = symbol.flags_field and Flags.STATIC.toLong() > 0L

        val name = symbol.name.toString()
        val methodPath = TreePath(currentPath, node.meth)
        val methodElement = trees.getElement(methodPath)
        val defContainer = LanguageUtils.getTopLevelClass(methodElement) as Symbol.ClassSymbol? ?: return super.visitMethodInvocation(node, p)
        
        val (useRange, refRange, defPath) = findReference(methodElement, name, defContainer, methodPath) ?: return super.visitMethodInvocation(node, p)

        indexer.emitUse(useRange, refRange, defPath)

        return super.visitMethodInvocation(node, p)
    }

    // does not match `var` or constructor calls
    override fun visitIdentifier(node: IdentifierTree?, p: Unit?): Unit? {
        val symbol = (node as JCTree.JCIdent).sym ?: return super.visitIdentifier(node, p)
        
        if(symbol is Symbol.PackageSymbol) return super.visitIdentifier(node, p)

        val name = symbol.name.toString()
        val defContainer = LanguageUtils.getTopLevelClass(symbol) as Symbol.ClassSymbol? ?: return super.visitIdentifier(node, p)

        val (useRange, refRange, defPath) = findReference(symbol, name, defContainer) ?: return super.visitIdentifier(node, p)
        
        indexer.emitUse(useRange, refRange, defPath)

        return super.visitIdentifier(node, p)
    }

    // function references eg test::myfunc or banana::new
    override fun visitMemberReference(node: MemberReferenceTree?, p: Unit?): Unit? {
        val symbol = (node as JCTree.JCMemberReference).sym ?: return super.visitMemberReference(node, p)
        
        val name = symbol.name.toString()
        
        val defContainer = LanguageUtils.getTopLevelClass(symbol) as Symbol.ClassSymbol? ?: return super.visitMemberReference(node, p)
        
        val (useRange, refRange, defPath) = findReference(symbol, name, defContainer) ?: return super.visitMemberReference(node, p)

        indexer.emitUse(useRange, refRange, defPath)
        
        return super.visitMemberReference(node, p)
    }
    
    private fun findReference(symbol: Element, symbolName: String, container: Symbol.ClassSymbol, path: TreePath = currentPath): ReferenceData? {
        val sourceFilePath = container.sourcefile ?: return null
        val defPath = Paths.get(sourceFilePath.name)
        if(sourceFilePath.name != compUnit.sourceFile.name)
            indexers[Paths.get(sourceFilePath.name)]?.index() ?: return null
        val refRange = findDefinition(symbol)?.range ?: return null

        val useRange = findLocation(currentPath, symbolName)?.range ?: return null
        return ReferenceData(useRange, refRange, defPath)
    }

    // from https://github.com/georgewfraser/java-language-server/blob/3555762fa35ab99575130911b3c930cc4d2d7b26/src/main/java/org/javacs/FindHelper.java
    private fun findDefinition(element: Element): Location? {
        val path = trees.getPath(element) ?: return null
        var name = element.simpleName
        if (name.contentEquals("<init>")) name = element.enclosingElement.simpleName
        return findLocation(path, name)
    }

    private fun findLocation(path: TreePath, name: CharSequence): Location? {
        val lines = path.compilationUnit.lineMap
        val pos = trees.sourcePositions
        var start = pos.getStartPosition(path.compilationUnit, path.leaf).toInt()
        var end = pos.getEndPosition(path.compilationUnit, path.leaf).toInt()
        if (end == -1) return null
        if (name.isNotEmpty()) {
            start = findNameIn(path.compilationUnit, name, start, end)
            end = start + name.length
        }
        val startLine = lines.getLineNumber(start.toLong()).toInt()
        val startColumn = lines.getColumnNumber(start.toLong()).toInt()
        val endLine = lines.getLineNumber(end.toLong()).toInt()
        val endColumn = lines.getColumnNumber(end.toLong()).toInt()
        val range = Range(
            Position(startLine - 1, startColumn - 1),
            Position(endLine - 1, endColumn - 1)
        )
        val uri = path.compilationUnit.sourceFile.toUri()
        return Location(uri.path, range)
    }

    // this will fail to find the correct position of method 'nom' in public <T extends Generic.nom> void nom() {
    private fun findNameIn(root: CompilationUnitTree, name: CharSequence, start: Int, end: Int): Int {
        val contents: CharSequence
        contents = try {
            root.sourceFile.getCharContent(true)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        val matcher = Pattern.compile("\\b$name\\b").matcher(contents)
        matcher.region(start, end)
        return if (matcher.find()) {
            matcher.start()
        } else -1
    }
}