package com.sunnysuperman.sqlgenerator.idea;

import com.intellij.psi.*;

import java.util.ArrayList;
import java.util.List;

public class AnnotationUtils {

    public static String getStringValue(PsiAnnotation annotation, String key) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(key);
        if (value instanceof PsiLiteralExpression) {
            return ((PsiLiteralExpression) value).getValue().toString();
        }
        return null;
    }

    public static String getEnumValue(PsiAnnotation annotation, String key) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(key);
        if (value instanceof PsiReferenceExpression) {
            PsiElement resolved = ((PsiReferenceExpression) value).resolve();
            if (resolved instanceof PsiEnumConstant) {
                PsiEnumConstant enumConstant = (PsiEnumConstant) resolved;
                return enumConstant.getName();
            }
        }
        return null;
    }

    public static boolean getBooleanValue(PsiAnnotation annotation, String key, boolean defaultValue) {
        String v = getStringValue(annotation, key);
        return v == null ? defaultValue : Boolean.parseBoolean(v.toString());
    }

    public static int getIntValue(PsiAnnotation annotation, String key, int defaultValue) {
        String v = getStringValue(annotation, key);
        return v == null ? defaultValue : Integer.parseInt(v.toString());
    }

    public static String[] getStringArrayValue(PsiAnnotation annotation, String key) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(key);
        if (value instanceof PsiArrayInitializerMemberValue) {
            PsiArrayInitializerMemberValue arrayInitializer = (PsiArrayInitializerMemberValue) value;
            PsiAnnotationMemberValue[] initializers = arrayInitializer.getInitializers();
            List<String> list = new ArrayList<>(initializers.length);
            for (PsiAnnotationMemberValue initializer : initializers) {
                if (initializer instanceof PsiLiteralExpression) {
                    PsiLiteralExpression literal = (PsiLiteralExpression) initializer;
                    Object v = literal.getValue();
                    list.add(v != null ? v.toString() : StringUtil.EMPTY);
                }
            }
            return list.toArray(new String[list.size()]);
        }
        String v = getStringValue(annotation, key);
        if (v != null) {
            return new String[]{v.toString()};
        }
        return new String[0];
    }
}
